#include <jni.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <vector>
#include <string>

#define TAG "PtyBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_clawsses_glasses_local_PtyProcess_nativeCreate(
        JNIEnv *env, jclass, jint cols, jint rows, jobjectArray envVars) {

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) { LOGE("posix_openpt: %s", strerror(errno)); return -1; }

    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        LOGE("grantpt/unlockpt: %s", strerror(errno));
        close(master); return -1;
    }

    char slavePath[256];
    if (ptsname_r(master, slavePath, sizeof(slavePath)) != 0) {
        LOGE("ptsname_r: %s", strerror(errno));
        close(master); return -1;
    }

    struct winsize ws{};
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ioctl(master, TIOCSWINSZ, &ws);

    // Copy env strings before fork — JNI is not valid in the child process
    std::vector<std::string> envStrings;
    if (envVars != nullptr) {
        jsize len = env->GetArrayLength(envVars);
        for (jsize i = 0; i < len; i++) {
            auto jstr = (jstring)env->GetObjectArrayElement(envVars, i);
            const char *str = env->GetStringUTFChars(jstr, nullptr);
            envStrings.emplace_back(str);
            env->ReleaseStringUTFChars(jstr, str);
        }
    }

    pid_t pid = fork();
    if (pid < 0) { LOGE("fork: %s", strerror(errno)); close(master); return -1; }

    if (pid == 0) {
        // Child: become session leader, attach slave PTY as stdio, exec shell
        setsid();

        int slave = open(slavePath, O_RDWR);
        if (slave < 0) _exit(1);

        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        for (const auto &e : envStrings) putenv(strdup(e.c_str()));

        execl("/system/bin/sh", "sh", nullptr);
        _exit(1);
    }

    // Parent: return master fd; closing it later sends SIGHUP to the shell
    return master;
}

JNIEXPORT void JNICALL
Java_com_clawsses_glasses_local_PtyProcess_nativeResize(
        JNIEnv *, jclass, jint fd, jint cols, jint rows) {
    struct winsize ws{};
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ioctl(fd, TIOCSWINSZ, &ws);
}

} // extern "C"
