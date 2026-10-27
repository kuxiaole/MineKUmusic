#version 150

// OpenGL 3.2 core 版本（1.20.1 以上）：内容和 1.8.9 版一样，只是改了输入输出的写法
out vec4 fragColor;

uniform sampler2D u_Texture;
uniform float u_Opacity;

in vec2 uv;

void main() {
    vec4 color = texture(u_Texture, uv);
    fragColor = vec4(color.rgb, color.a * u_Opacity);
}