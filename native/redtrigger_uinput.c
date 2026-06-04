#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <linux/uinput.h>
#include <linux/input.h>

#define VERSION "4"

/* Global fd for signal handler cleanup */
static volatile int g_uinput_fd = -1;

static void cleanup_handler(int sig) {
    fprintf(stderr, "LOG caught signal %d, destroying uinput device\n", sig);
    fflush(stderr);
    if (g_uinput_fd >= 0) {
        ioctl(g_uinput_fd, UI_DEV_DESTROY);
        close(g_uinput_fd);
        g_uinput_fd = -1;
    }
    _exit(0);
}

void emit(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    ssize_t n = write(fd, &ie, sizeof(ie));
    if (n != sizeof(ie)) {
        fprintf(stderr, "LOG write failed: type=%d code=%d val=%d errno=%d (%s)\n",
                type, code, val, errno, strerror(errno));
        fflush(stderr);
    }
}

int main(void) {
    fprintf(stderr, "LOG uinput_injector v%s starting\n", VERSION);
    fflush(stderr);

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "LOG open /dev/uinput failed: errno=%d (%s)\n", errno, strerror(errno));
        fflush(stderr);
        return 1;
    }
    g_uinput_fd = fd;
    fprintf(stderr, "LOG /dev/uinput opened fd=%d\n", fd);
    fflush(stderr);

    /* Install signal handlers so device is cleaned up on kill */
    signal(SIGTERM, cleanup_handler);
    signal(SIGINT, cleanup_handler);
    signal(SIGHUP, cleanup_handler);

    /* Set up event types */
    int rc;
    rc = ioctl(fd, UI_SET_EVBIT, EV_KEY);
    fprintf(stderr, "LOG UI_SET_EVBIT EV_KEY rc=%d\n", rc);

    rc = ioctl(fd, UI_SET_EVBIT, EV_ABS);
    fprintf(stderr, "LOG UI_SET_EVBIT EV_ABS rc=%d\n", rc);

    /* Register gamepad buttons */
    rc = ioctl(fd, UI_SET_KEYBIT, BTN_GAMEPAD);
    fprintf(stderr, "LOG UI_SET_KEYBIT BTN_GAMEPAD(%d) rc=%d\n", BTN_GAMEPAD, rc);

    rc = ioctl(fd, UI_SET_KEYBIT, BTN_TL);
    fprintf(stderr, "LOG UI_SET_KEYBIT BTN_TL(%d) rc=%d\n", BTN_TL, rc);

    rc = ioctl(fd, UI_SET_KEYBIT, BTN_TR);
    fprintf(stderr, "LOG UI_SET_KEYBIT BTN_TR(%d) rc=%d\n", BTN_TR, rc);

    /* Register ABS axes for gamepad classification */
    rc = ioctl(fd, UI_SET_ABSBIT, ABS_X);
    fprintf(stderr, "LOG UI_SET_ABSBIT ABS_X rc=%d\n", rc);

    rc = ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    fprintf(stderr, "LOG UI_SET_ABSBIT ABS_Y rc=%d\n", rc);

    fflush(stderr);

    /* Configure device identity */
    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "RedTrigger Virtual Gamepad");
    uud.absmin[ABS_X] = -128;
    uud.absmax[ABS_X] = 127;
    uud.absmin[ABS_Y] = -128;
    uud.absmax[ABS_Y] = 127;
    uud.id.bustype = BUS_USB;
    uud.id.vendor  = 0x045e;  /* Microsoft */
    uud.id.product = 0x028e;  /* Xbox 360 Controller */
    uud.id.version = 1;

    fprintf(stderr, "LOG device: name='%s' bus=0x%x vendor=0x%04x product=0x%04x\n",
            uud.name, uud.id.bustype, uud.id.vendor, uud.id.product);
    fflush(stderr);

    ssize_t n = write(fd, &uud, sizeof(uud));
    fprintf(stderr, "LOG write uinput_user_dev: %zd bytes (expected %zu)\n", n, sizeof(uud));
    fflush(stderr);

    rc = ioctl(fd, UI_DEV_CREATE);
    fprintf(stderr, "LOG UI_DEV_CREATE rc=%d errno=%d\n", rc, errno);
    fflush(stderr);

    if (rc < 0) {
        fprintf(stderr, "LOG UI_DEV_CREATE failed: %s\n", strerror(errno));
        fflush(stderr);
        close(fd);
        return 1;
    }

    /* Verify: check /proc/bus/input/devices for our device */
    fprintf(stderr, "LOG sleeping 200ms for device registration\n");
    fflush(stderr);
    usleep(200000);

    FILE *devs = fopen("/proc/bus/input/devices", "r");
    if (devs) {
        char buf[512];
        int found = 0;
        while (fgets(buf, sizeof(buf), devs)) {
            if (strstr(buf, "RedTrigger")) {
                found = 1;
                fprintf(stderr, "LOG DEVICE_FOUND: %s", buf);
                /* Print next few lines for full device info */
                for (int i = 0; i < 5 && fgets(buf, sizeof(buf), devs); i++) {
                    fprintf(stderr, "LOG DEVICE_INFO: %s", buf);
                    if (buf[0] == '\n') break;
                }
                break;
            }
        }
        fclose(devs);
        if (!found) {
            fprintf(stderr, "LOG WARNING: device not found in /proc/bus/input/devices\n");
        }
    }
    fflush(stderr);

    printf("READY v%s\n", VERSION);
    fflush(stdout);

    fprintf(stderr, "LOG ready, waiting for commands on stdin\n");
    fflush(stderr);

    char line[256];
    int count = 0;
    while (fgets(line, sizeof(line), stdin)) {
        int code, val;
        if (sscanf(line, "%d %d", &code, &val) == 2) {
            emit(fd, EV_KEY, code, val);
            emit(fd, EV_SYN, SYN_REPORT, 0);
            count++;
            fprintf(stderr, "LOG inject #%d: code=%d val=%d\n", count, code, val);
            fflush(stderr);
            printf("OK %d %d\n", code, val);
            fflush(stdout);
        } else {
            fprintf(stderr, "LOG bad input: '%s'\n", line);
            fflush(stderr);
        }
    }

    fprintf(stderr, "LOG stdin closed, cleaning up (injected %d events)\n", count);
    fflush(stderr);

    g_uinput_fd = -1;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
