#version 150

// OpenGL 3.2 core 版本（1.20.1 以上）：内容和 1.8.9 版一样，只是改了输入输出的写法
in vec2 a_Pos;
in vec2 a_UV;

uniform vec2 u_ScreenSize;
uniform mat4 u_Transform;

out vec2 v_Position; // 逻辑坐标（变换前）
out vec2 v_Screen;   // 屏幕像素坐标（y 向下）

void main() {
    v_Position = a_Pos;
    vec4 vert = u_Transform * vec4(a_Pos, 0.0, 1.0);
    v_Screen = vert.xy;
    vec2 pos = vert.xy / u_ScreenSize * 2.0 - 1.0;
    gl_Position = vec4(pos.x, -pos.y, vert.z, 1.0);
}
