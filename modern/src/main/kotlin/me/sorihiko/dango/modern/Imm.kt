package me.sorihiko.dango.modern

import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

import java.nio.FloatBuffer

/**
 * 高版本 OpenGL（3.2 core）里没有 glBegin / glVertex 了。这里模拟 1.8.9 那种"一个个点交进去再画"的写法，
 * 让移植过来的代码改动最少：
 *
 *     Imm.begin()
 *     Imm.tex(u, v); Imm.vertex(x, y)   // 四个点一组，顺序和 GL_QUADS 一样
 *     Imm.end()
 *
 * [end] 用当前绑定的着色器画（顶点属性：0 = a_Pos，1 = a_UV）；
 * [endFixed] 用 [Fixed] 的固定管线替代品画（颜色 × 贴图，矩阵来自 [Fixed]），相当于 1.8.9 的固定管线。
 */
object Imm {
    const val ATTR_POS = 0
    const val ATTR_UV = 1

    private var vao = 0
    private var vbo = 0
    private var data = FloatArray(4 * 6 * 256)
    private var count = 0          // 已交进来的点数（按四边形算）
    private val quad = FloatArray(16)
    private var corner = 0
    private var u = 0f
    private var v = 0f
    private var upload: FloatBuffer = BufferUtils.createFloatBuffer(data.size)

    /** 当前这一批是否要贴图（Fixed 用） */
    private var textured = true

    fun begin(textured: Boolean = true) {
        count = 0
        corner = 0
        this.textured = textured
    }

    fun tex(u: Float, v: Float) {
        this.u = u
        this.v = v
    }

    fun vertex(x: Float, y: Float) {
        val o = corner * 4
        quad[o] = x; quad[o + 1] = y; quad[o + 2] = u; quad[o + 3] = v
        corner++
        if (corner == 4) {
            corner = 0
            // 四边形 → 两个三角形 (0,1,2) (0,2,3)
            ensure(count + 6)
            putCorner(0); putCorner(1); putCorner(2)
            putCorner(0); putCorner(2); putCorner(3)
        }
    }

    /**
     * 把四边形的第 [i] 个角抄进顶点数组。
     * 逐个赋值，不用临时数组和 System.arraycopy：Java 25 的 C2 编译器在优化原来那种写法
     * （被内联进画字的循环里）时会自己崩掉，整个游戏直接闪退。
     */
    private fun putCorner(i: Int) {
        val src = i * 4
        val dst = count * 4
        val d = data
        d[dst] = quad[src]
        d[dst + 1] = quad[src + 1]
        d[dst + 2] = quad[src + 2]
        d[dst + 3] = quad[src + 3]
        count++
    }

    private fun ensure(vertices: Int) {
        if (vertices * 4 <= data.size) return
        data = data.copyOf(maxOf(data.size * 2, vertices * 4))
        upload = BufferUtils.createFloatBuffer(data.size)
    }

    fun end() = draw(false)

    fun endFixed() = draw(true)

    private fun draw(fixed: Boolean) {
        if (count == 0) return
        if (vao == 0) {
            vao = GL30.glGenVertexArrays()
            vbo = GL15.glGenBuffers()
        }
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)

        if (fixed) Fixed.use(textured)

        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        upload.clear()
        upload.put(data, 0, count * 4)
        upload.flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW)
        GL20.glEnableVertexAttribArray(ATTR_POS)
        GL20.glVertexAttribPointer(ATTR_POS, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(ATTR_UV)
        GL20.glVertexAttribPointer(ATTR_UV, 2, GL11.GL_FLOAT, false, 16, 8L)
        McCompat.beforeDraw()
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count)

        // 原样换回去：Minecraft 记着它自己绑的是哪个 VAO / 着色器
        GL30.glBindVertexArray(prevVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuffer)
        if (fixed) GL20.glUseProgram(prevProgram)
        count = 0
    }
}

/**
 * 固定管线的替代品：当前颜色 + 矩阵（相当于 1.8.9 的 glColor4f 和 glPushMatrix / glTranslatef …）。
 *
 * [pose] 是"模型"矩阵（不含投影）；真正画的时候乘上 Minecraft 当前的投影矩阵和视图矩阵，
 * 这样和原版的文字（Font）用的是同一套坐标。
 */
object Fixed {
    val pose = Matrix4f()
    private val stack = ArrayDeque<Matrix4f>()
    val color = floatArrayOf(1f, 1f, 1f, 1f)

    fun push() {
        stack.addLast(Matrix4f(pose))
    }

    fun pop() {
        stack.removeLastOrNull()?.let { pose.set(it) }
    }

    fun reset(base: Matrix4f? = null) {
        stack.clear()
        if (base != null) pose.set(base) else pose.identity()
    }

    fun color(r: Float, g: Float, b: Float, a: Float) {
        color[0] = r; color[1] = g; color[2] = b; color[3] = a
    }

    // ---------------------------------------------------------------- 着色器

    private const val VERT = """
#version 150
in vec2 a_Pos;
in vec2 a_UV;
uniform mat4 u_MVP;
out vec2 uv;
void main() {
    gl_Position = u_MVP * vec4(a_Pos, 0.0, 1.0);
    uv = a_UV;
}
"""

    private const val FRAG = """
#version 150
uniform sampler2D u_Texture;
uniform vec4 u_Color;
uniform int u_Textured;
in vec2 uv;
out vec4 fragColor;
void main() {
    vec4 c = u_Color;
    if (u_Textured != 0) c *= texture(u_Texture, uv);
    fragColor = c;
}
"""

    private var program = 0
    private var locMvp = 0
    private var locColor = 0
    private var locTextured = 0
    private var locTexture = 0
    private val matBuf = BufferUtils.createFloatBuffer(16)
    private val mvp = Matrix4f()

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error("Fixed shader compile failed: " + GL20.glGetShaderInfoLog(id, 4096))
        }
        return id
    }

    private fun ensure() {
        if (program != 0) return
        val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG)
        val p = GL20.glCreateProgram()
        GL20.glAttachShader(p, vs)
        GL20.glAttachShader(p, fs)
        GL20.glBindAttribLocation(p, Imm.ATTR_POS, "a_Pos")
        GL20.glBindAttribLocation(p, Imm.ATTR_UV, "a_UV")
        GL20.glLinkProgram(p)
        GL20.glDeleteShader(vs)
        GL20.glDeleteShader(fs)
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("Fixed shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
        }
        program = p
        locMvp = GL20.glGetUniformLocation(p, "u_MVP")
        locColor = GL20.glGetUniformLocation(p, "u_Color")
        locTextured = GL20.glGetUniformLocation(p, "u_Textured")
        locTexture = GL20.glGetUniformLocation(p, "u_Texture")
    }

    /** 绑定固定管线着色器，设好矩阵和颜色（贴图用当前 0 号单元上绑的那张） */
    fun use(textured: Boolean) {
        ensure()
        GL20.glUseProgram(program)
        mvp.set(McCompat.projection()).mul(McCompat.modelView()).mul(pose)
        matBuf.clear()
        mvp.get(matBuf)
        GL20.glUniformMatrix4fv(locMvp, false, matBuf)
        GL20.glUniform4f(locColor, color[0], color[1], color[2], color[3])
        GL20.glUniform1i(locTextured, if (textured) 1 else 0)
        GL20.glUniform1i(locTexture, 0)
    }
}
