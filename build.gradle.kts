buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath(kotlin("gradle-plugin", version = "2.4.0"))
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
