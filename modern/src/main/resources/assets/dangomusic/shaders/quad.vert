#version 150

// OpenGL 3.2 core 版本（1.20.1 以上）：内容和 1.8.9 版一样，只是改了输入输出的写法
in vec2 a_Pos;
in vec2 a_UV;

out vec2 uv;

void main(){
    gl_Position = vec4(a_Pos, 0.0, 1.0);
    uv = a_UV;
}