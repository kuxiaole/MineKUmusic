// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
dependencies {
    "api"(project(":protocol"))
    "api"(project(":sources"))
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
    "testImplementation"("junit:junit:4.13.2")
    "testImplementation"("com.google.code.gson:gson:2.2.4")
    "testImplementation"("org.apache.logging.log4j:log4j-api:2.17.2")
}
