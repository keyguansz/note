- Tools
    1. freeline
Freeline 是蚂蚁金服旗下平台蚂蚁聚宝 Android 团队开发的一款针对 Android 平台的增量编译工具，它可以充分利用缓存文件，在几秒钟内迅速地对代码的改动进行编译并部署到设备上，有效地减少了日常开发中的大量重新编译与安装的耗时，现已开源。
### 如何使用？
1. 首先你的电脑上应该安装 Python，如果没有安装，请自行搜索安装，相信你可以搞得定。
1. 其次配置 project-level 的 build.gradle，加入 freeline-gradle 的依赖：
<code>
//```java
// TODO 怎么列表+ 代码呢
 // project/build.gradle
 buildscript {
     repositories {
         jcenter()
     }
     dependencies {
         classpath 'com.antfortune.freeline:gradle:0.8.2'
     }
   }
 //```
</code>
1. 然后，在你的主 module 的 build.gradle 中，一般也就是你 app 目录下的 build.gradle 文件，应用 freeline 插件的依赖，记得点击 Sync 按钮进行同步下载
1. 最后，执行命令：
- 方法1-在 Android Studio 中，通过以下路径 Preferences → Plugins → Browse repositories，搜索“freeline”，并安装，之后你会看到工具栏有如下按钮：
 ![](as-img/freeline.jpeg)
- 方法2-在命令行执行以下命令来下载 freeline 的 python 和二进制依赖，注意切换到项目目录执行：
Windows[CMD]: gradlew initFreeline
Linux/Mac: ./gradlew initFreeline

```java
// app/build.gradle
apply plugin: 'com.antfortune.freeline'

android {
    ...
}
```
### 坑
- 	Freeline Plugin: command 'python' not found.

插件
## REF
- [ANDROID 秒级编译 FREELINE](http://stormzhang.com/2016/12/02/android-seconds-build-freeline/)
- [freeline github](https://github.com/alibaba/freeline)
