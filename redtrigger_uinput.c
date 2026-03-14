#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <sys/time.h>

void emit(int fd, int type, int code, int val) {
    struct input_event ie;
    gettimeofday(&ie.time, NULL);
    ie.type = type;
    ie.code = code;
    ie.value = val;
    write(fd, &ie, sizeof(ie));
}

int main() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput");
        return 1;
    }

    // Enable key events
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    
    // Enable shoulder buttons (Gamepad L1/R1)
    ioctl(fd, UI_SET_KEYBIT, BTN_TL);  // L1
    ioctl(fd, UI_SET_KEYBIT, BTN_TR);  // R1
    ioctl(fd, UI_SET_KEYBIT, BTN_TL2); // L2
    ioctl(fd, UI_SET_KEYBIT, BTN_TR2); // R2
    
    // Enable standard keys as fallbacks
    ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEUP);
    ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEDOWN);

    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "RedTrigger Virtual Gamepad");
    uud.id.bustype = BUS_USB;
    uud.id.vendor  = 0x1234;
    uud.id.product = 0x5678;
    uud.id.version = 1;

    write(fd, &uud, sizeof(uud));
    
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE");
        close(fd);
        return 1;
    }

    printf("READY\n");
    fflush(stdout);

    char line[64];
    while (fgets(line, sizeof(line), stdin)) {
        if (strncmp(line, "QUIT", 4) == 0) break;

        int code = -1;
        int val = -1;

        if (strstr(line, "L1")) code = BTN_TL;
        else if (strstr(line, "R1")) code = BTN_TR;
        else if (strstr(line, "L2")) code = BTN_TL2;
        else if (strstr(line, "R2")) code = BTN_TR2;
        else if (strstr(line, "VOLUP")) code = KEY_VOLUMEUP;
        else if (strstr(line, "VOLDOWN")) code = KEY_VOLUMEDOWN;

        if (strstr(line, "DOWN")) val = 1;
        else if (strstr(line, "UP")) val = 0;

        if (code != -1 && val != -1) {
            emit(fd, EV_KEY, code, val);
            emit(fd, EV_SYN, SYN_REPORT, 0);
            printf("OK %d %d\n", code, val);
        } else {
            printf("ERR invalid command\n");
        }
        fflush(stdout);
    }

    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
