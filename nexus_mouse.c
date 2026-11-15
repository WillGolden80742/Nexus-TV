#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <linux/uinput.h>

static int emit(int fd, int type, int code, int val) {
    if (fd < 0) return -1;
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    gettimeofday(&ie.time, NULL);
    ie.type = type;
    ie.code = code;
    ie.value = val;
    ssize_t ret = write(fd, &ie, sizeof(ie));
    return (int)ret;
}

int main(int argc, char **argv) {
    // 1. Pure Keyboard Device: "Nexus Virtual Remote" (vendor: 0x1234, product: 0x5678)
    int kfd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (kfd < 0) {
        perror("open /dev/uinput for keyboard");
        return 1;
    }

    ioctl(kfd, UI_SET_EVBIT, EV_KEY);
    ioctl(kfd, UI_SET_EVBIT, EV_SYN);

    for (int k = 1; k < 256; k++) {
        ioctl(kfd, UI_SET_KEYBIT, k);
    }
    ioctl(kfd, UI_SET_KEYBIT, KEY_OK);
    ioctl(kfd, UI_SET_KEYBIT, KEY_SELECT);

    struct uinput_user_dev kdev;
    memset(&kdev, 0, sizeof(kdev));
    snprintf(kdev.name, UINPUT_MAX_NAME_SIZE, "Nexus Virtual Remote");
    kdev.id.bustype = BUS_USB;
    kdev.id.vendor  = 0x1234;
    kdev.id.product = 0x5678;
    kdev.id.version = 1;

    if (write(kfd, &kdev, sizeof(kdev)) < 0 || ioctl(kfd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE keyboard");
        close(kfd);
        return 1;
    }
    printf("Nexus Virtual Remote (pure keyboard) created successfully.\n");

    // 2. Pure Pointer Device: "Nexus Virtual Mouse" (vendor: 0x1234, product: 0x5679)
    int mfd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (mfd >= 0) {
        ioctl(mfd, UI_SET_EVBIT, EV_KEY);
        ioctl(mfd, UI_SET_EVBIT, EV_REL);
        ioctl(mfd, UI_SET_EVBIT, EV_SYN);

        ioctl(mfd, UI_SET_KEYBIT, BTN_LEFT);
        ioctl(mfd, UI_SET_KEYBIT, BTN_RIGHT);
        ioctl(mfd, UI_SET_KEYBIT, BTN_MIDDLE);

        ioctl(mfd, UI_SET_RELBIT, REL_X);
        ioctl(mfd, UI_SET_RELBIT, REL_Y);
        ioctl(mfd, UI_SET_RELBIT, REL_WHEEL);

        struct uinput_user_dev mdev;
        memset(&mdev, 0, sizeof(mdev));
        snprintf(mdev.name, UINPUT_MAX_NAME_SIZE, "Nexus Virtual Mouse");
        mdev.id.bustype = BUS_USB;
        mdev.id.vendor  = 0x1234;
        mdev.id.product = 0x5679;
        mdev.id.version = 1;

        if (write(mfd, &mdev, sizeof(mdev)) < 0 || ioctl(mfd, UI_DEV_CREATE) < 0) {
            perror("UI_DEV_CREATE mouse");
            close(mfd);
            mfd = -1;
        } else {
            printf("Nexus Virtual Mouse created successfully.\n");
            // Move cursor off-screen initially to keep view clean
            emit(mfd, EV_REL, REL_X, 3000);
            emit(mfd, EV_REL, REL_Y, 3000);
            emit(mfd, EV_SYN, SYN_REPORT, 0);
        }
    }
    fflush(stdout);

    // UDP server on port 8766
    int sfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sfd < 0) {
        perror("socket");
        return 1;
    }

    int opt = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(8766);
    addr.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind 8766");
        return 1;
    }

    char buf[256];
    while (1) {
        int n = recv(sfd, buf, sizeof(buf) - 1, 0);
        if (n <= 0) continue;
        buf[n] = '\0';

        char cmd = buf[0];
        if (cmd == 'D') {
            int key = 0;
            if (sscanf(buf + 1, "%d", &key) == 1 && key > 0) {
                emit(kfd, EV_KEY, key, 1);
                emit(kfd, EV_SYN, SYN_REPORT, 0);
            }
        } else if (cmd == 'U') {
            int key = 0;
            if (sscanf(buf + 1, "%d", &key) == 1 && key > 0) {
                emit(kfd, EV_KEY, key, 0);
                emit(kfd, EV_SYN, SYN_REPORT, 0);
            }
        } else if (cmd == 'K') {
            int key = 0;
            if (sscanf(buf + 1, "%d", &key) == 1 && key > 0) {
                emit(kfd, EV_KEY, key, 1);
                emit(kfd, EV_SYN, SYN_REPORT, 0);
                usleep(25000);
                emit(kfd, EV_KEY, key, 0);
                emit(kfd, EV_SYN, SYN_REPORT, 0);
            }
        } else if (cmd == 'C') {
            // Confirm/OK action: ALWAYS emit KEY_ENTER (28) on pure keyboard, NEVER a mouse click!
            emit(kfd, EV_KEY, 28, 1);
            emit(kfd, EV_SYN, SYN_REPORT, 0);
            usleep(25000);
            emit(kfd, EV_KEY, 28, 0);
            emit(kfd, EV_SYN, SYN_REPORT, 0);
        } else if (cmd == 'M' && mfd >= 0) {
            int dx = 0, dy = 0;
            if (sscanf(buf + 1, "%d %d", &dx, &dy) >= 1) {
                if (dx != 0) emit(mfd, EV_REL, REL_X, dx);
                if (dy != 0) emit(mfd, EV_REL, REL_Y, dy);
                emit(mfd, EV_SYN, SYN_REPORT, 0);
            }
        } else if (cmd == 'W' && mfd >= 0) {
            int w = 0;
            if (sscanf(buf + 1, "%d", &w) == 1) {
                emit(mfd, EV_REL, REL_WHEEL, w);
                emit(mfd, EV_SYN, SYN_REPORT, 0);
            }
        }
    }

    ioctl(kfd, UI_DEV_DESTROY);
    close(kfd);
    if (mfd >= 0) {
        ioctl(mfd, UI_DEV_DESTROY);
        close(mfd);
    }
    close(sfd);
    return 0;
}
