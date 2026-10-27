#version 150

// OpenGL 3.2 core 版本（1.20.1 以上）：内容和 1.8.9 版一样，只是改了输入输出的写法
out vec4 fragColor;

// 9-tap 高斯（线性采样优化为 5 次采样）
uniform sampler2D u_Texture;
uniform vec2 u_Direction; // texelSize * 方向 * 扩散

in vec2 uv;

void main() {
    vec3 c = texture(u_Texture, uv).rgb * 0.2270270270;
    c += texture(u_Texture, uv + u_Direction * 1.3846153846).rgb * 0.3162162162;
    c += texture(u_Texture, uv - u_Direction * 1.3846153846).rgb * 0.3162162162;
    c += texture(u_Texture, uv + u_Direction * 3.2307692308).rgb * 0.0702702703;
    c += texture(u_Texture, uv - u_Direction * 3.2307692308).rgb * 0.0702702703;
    fragColor = vec4(c, 1.0);
}
