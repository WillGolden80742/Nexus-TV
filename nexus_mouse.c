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
#include <pthread.h>
#include <poll.h>
#include <stdint.h>

static int g_kfd = -1;
static int g_mfd = -1;
static pthread_mutex_t g_emit_mutex = PTHREAD_MUTEX_INITIALIZER;

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

static uint64_t get_now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (uint64_t)tv.tv_sec * 1000ULL + (uint64_t)tv.tv_usec / 1000ULL;
}

static const char *key_name(int key) {
    switch (key) {
        case KEY_UP: return "KEY_UP";
        case KEY_DOWN: return "KEY_DOWN";
        case KEY_LEFT: return "KEY_LEFT";
        case KEY_RIGHT: return "KEY_RIGHT";
        case KEY_ENTER: return "KEY_ENTER (OK)";
        case KEY_BACK: return "KEY_BACK";
        case KEY_HOME: return "KEY_HOME";
        case KEY_MENU: return "KEY_MENU";
        case KEY_VOLUMEUP: return "KEY_VOLUMEUP";
        case KEY_VOLUMEDOWN: return "KEY_VOLUMEDOWN";
        case KEY_MUTE: return "KEY_MUTE";
        case KEY_POWER: return "KEY_POWER";
        case KEY_PAGEUP: return "KEY_PAGEUP";
        case KEY_PAGEDOWN: return "KEY_PAGEDOWN";
        case KEY_PLAYPAUSE: return "KEY_PLAYPAUSE";
        default: return "OTHER_KEY";
    }
}

static int map_lg_scancode(int scan) {
    int raw = (~scan) & 0xff;
    switch (scan) {
        case 0xbf: return KEY_UP;         // Up (raw 0x40)
        case 0xbe: return KEY_DOWN;       // Down (raw 0x41)
        case 0xf8: return KEY_LEFT;       // Left (raw 0x07)
        case 0xf9: return KEY_RIGHT;      // Right (raw 0x06)
        case 0xbb: return KEY_ENTER;      // OK (raw 0x44)
        case 0xd7: return KEY_BACK;       // Back (raw 0x28)
        case 0xa4: return KEY_BACK;       // Exit (raw 0x5b)
        case 0x83: return KEY_HOME;       // Home (raw 0x7c)
        case 0x81: return KEY_HOME;       // Home alt (raw 0x7e)
        case 0xde: return KEY_HOME;       // Home alt 2 (raw 0x21)
        case 0xbc: return KEY_MENU;       // Menu / Settings (raw 0x43)
        case 0xfd: return KEY_VOLUMEUP;   // Vol+ (raw 0x02)
        case 0xfc: return KEY_VOLUMEDOWN; // Vol- (raw 0x03)
        case 0xf6: return KEY_MUTE;       // Mute (raw 0x09)
        case 0xf7: return KEY_POWER;      // Power (raw 0x08)
        case 0xef: return KEY_POWER;      // Power alt (raw 0x10)
        case 0xff: return KEY_PAGEUP;     // Ch+ (raw 0x00)
        case 0xfe: return KEY_PAGEDOWN;   // Ch- (raw 0x01)
        case 0x4f: return KEY_PLAYPAUSE;  // Play (raw 0xb0)
        case 0x45: return KEY_PLAYPAUSE;  // Pause (raw 0xba)
        case 0x4e: return KEY_STOP;       // Stop (raw 0xb1)
        case 0x70: return KEY_FASTFORWARD;// FF (raw 0x8f)
        case 0x71: return KEY_REWIND;     // RW (raw 0x8e)
        case 0x8d: return KEY_RED;        // Red (raw 0x72)
        case 0x8e: return KEY_GREEN;      // Green (raw 0x71)
        case 0x9c: return KEY_YELLOW;     // Yellow (raw 0x63)
        case 0x9e: return KEY_BLUE;       // Blue (raw 0x61)
        case 0xee: return KEY_1;
        case 0xed: return KEY_2;
        case 0xec: return KEY_3;
        case 0xeb: return KEY_4;
        case 0xea: return KEY_5;
        case 0xe9: return KEY_6;
        case 0xe8: return KEY_7;
        case 0xe7: return KEY_8;
        case 0xe6: return KEY_9;
        case 0xe5: return KEY_0;
        default:
            // Check raw if driver passed un-inverted value
            if (raw == 0x40) return KEY_UP;
            if (raw == 0x41) return KEY_DOWN;
            if (raw == 0x07) return KEY_LEFT;
            if (raw == 0x06) return KEY_RIGHT;
            if (raw == 0x44) return KEY_ENTER;
            if (raw == 0x28 || raw == 0x5b) return KEY_BACK;
            if (raw == 0x7c || raw == 0x7e || raw == 0x21) return KEY_HOME;
            if (raw == 0x43) return KEY_MENU;
            if (raw == 0x02) return KEY_VOLUMEUP;
            if (raw == 0x03) return KEY_VOLUMEDOWN;
            if (raw == 0x09) return KEY_MUTE;
            if (raw == 0x08 || raw == 0x10) return KEY_POWER;
            return 0;
    }
}

static void *ir_worker_thread(void *arg) {
    (void)arg;
    system("echo 1 > /sys/module/rockchip_pwm_remotectl/parameters/code_print 2>/dev/null");

    int fd = open("/dev/kmsg", O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("ir_worker: open /dev/kmsg");
        return NULL;
    }
    lseek(fd, 0, SEEK_END);

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;

    int is_lg = 1; // Default to 1 so LG remotes work right away
    int held_key = 0;
    uint64_t held_key_last_rx_ms = 0;

    char buf[1024];

    printf("Nexus IR Worker thread started, listening for LG TV remote...\n");
    fflush(stdout);

    while (1) {
        int ret = poll(&pfd, 1, 40);
        uint64_t now = get_now_ms();

        // Release held key (e.g. OK) if no repeat arrived for 320ms
        if (held_key > 0 && (now - held_key_last_rx_ms > 320)) {
            pthread_mutex_lock(&g_emit_mutex);
            emit(g_kfd, EV_KEY, held_key, 0);
            emit(g_kfd, EV_SYN, SYN_REPORT, 0);
            pthread_mutex_unlock(&g_emit_mutex);
            printf(">>> [IR] Key RELEASE: %s (%d)\n", key_name(held_key), held_key);
            fflush(stdout);
            held_key = 0;
        }

        if (ret > 0 && (pfd.revents & POLLIN)) {
            while (1) {
                ssize_t n = read(fd, buf, sizeof(buf) - 1);
                if (n <= 0) break;
                buf[n] = '\0';

                char *uc = strstr(buf, "USERCODE=0x");
                if (uc) {
                    unsigned int code = 0;
                    if (sscanf(uc + 11, "%x", &code) == 1) {
                        // LG TV address is 0x04 (or 0xfb04)
                        if ((code & 0xff) == 0x04 || code == 0xfb04) {
                            is_lg = 1;
                        } else {
                            is_lg = 0;
                        }
                    }
                }

                char *gd = strstr(buf, "RMC_GETDATA=");
                if (gd && is_lg) {
                    unsigned int scancode = 0;
                    if (sscanf(gd + 12, "%x", &scancode) == 1) {
                        int key = map_lg_scancode(scancode);
                        now = get_now_ms();

                        if (key > 0) {
                            printf(">>> [IR] Key: %s (%d), scancode: 0x%02x\n",
                                   key_name(key), key, scancode);
                            fflush(stdout);

                            if (key == KEY_ENTER) {
                                // OK button supports long-press hold
                                if (held_key == 0) {
                                    pthread_mutex_lock(&g_emit_mutex);
                                    emit(g_kfd, EV_KEY, KEY_ENTER, 1);
                                    emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                                    pthread_mutex_unlock(&g_emit_mutex);
                                    held_key = KEY_ENTER;
                                    printf(">>> [IR] OK DOWN\n");
                                    fflush(stdout);
                                }
                                held_key_last_rx_ms = now;
                            } else {
                                // If OK was held, release it before emitting another key
                                if (held_key > 0) {
                                    pthread_mutex_lock(&g_emit_mutex);
                                    emit(g_kfd, EV_KEY, held_key, 0);
                                    emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                                    pthread_mutex_unlock(&g_emit_mutex);
                                    held_key = 0;
                                }
                                // Navigation and other action keys: crisp tap
                                pthread_mutex_lock(&g_emit_mutex);
                                emit(g_kfd, EV_KEY, key, 1);
                                emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                                usleep(25000); // 25ms
                                emit(g_kfd, EV_KEY, key, 0);
                                emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                                pthread_mutex_unlock(&g_emit_mutex);
                            }
                        }
                    }
                }
            }
        }
    }
    close(fd);
    return NULL;
}

int main(int argc, char **argv) {
    // 1. Pure Keyboard Device: "Nexus Virtual Remote" (vendor: 0x1234, product: 0x5678)
    g_kfd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_kfd < 0) {
        perror("open /dev/uinput for keyboard");
        return 1;
    }

    ioctl(g_kfd, UI_SET_EVBIT, EV_KEY);
    ioctl(g_kfd, UI_SET_EVBIT, EV_SYN);

    for (int k = 1; k < 512; k++) {
        ioctl(g_kfd, UI_SET_KEYBIT, k);
    }
    ioctl(g_kfd, UI_SET_KEYBIT, KEY_OK);
    ioctl(g_kfd, UI_SET_KEYBIT, KEY_SELECT);

    struct uinput_user_dev kdev;
    memset(&kdev, 0, sizeof(kdev));
    snprintf(kdev.name, UINPUT_MAX_NAME_SIZE, "Nexus Virtual Remote");
    kdev.id.bustype = BUS_USB;
    kdev.id.vendor  = 0x1234;
    kdev.id.product = 0x5678;
    kdev.id.version = 1;

    if (write(g_kfd, &kdev, sizeof(kdev)) < 0 || ioctl(g_kfd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE keyboard");
        close(g_kfd);
        return 1;
    }
    printf("Nexus Virtual Remote (pure keyboard) created successfully.\n");

    // 2. Pure Pointer Device: "Nexus Virtual Mouse" (vendor: 0x1234, product: 0x5679)
    g_mfd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_mfd >= 0) {
        ioctl(g_mfd, UI_SET_EVBIT, EV_KEY);
        ioctl(g_mfd, UI_SET_EVBIT, EV_REL);
        ioctl(g_mfd, UI_SET_EVBIT, EV_SYN);

        ioctl(g_mfd, UI_SET_KEYBIT, BTN_LEFT);
        ioctl(g_mfd, UI_SET_KEYBIT, BTN_RIGHT);
        ioctl(g_mfd, UI_SET_KEYBIT, BTN_MIDDLE);

        ioctl(g_mfd, UI_SET_RELBIT, REL_X);
        ioctl(g_mfd, UI_SET_RELBIT, REL_Y);
        ioctl(g_mfd, UI_SET_RELBIT, REL_WHEEL);

        struct uinput_user_dev mdev;
        memset(&mdev, 0, sizeof(mdev));
        snprintf(mdev.name, UINPUT_MAX_NAME_SIZE, "Nexus Virtual Mouse");
        mdev.id.bustype = BUS_USB;
        mdev.id.vendor  = 0x1234;
        mdev.id.product = 0x5679;
        mdev.id.version = 1;

        if (write(g_mfd, &mdev, sizeof(mdev)) < 0 || ioctl(g_mfd, UI_DEV_CREATE) < 0) {
            perror("UI_DEV_CREATE mouse");
            close(g_mfd);
            g_mfd = -1;
        } else {
            printf("Nexus Virtual Mouse created successfully.\n");
            // Move cursor off-screen initially to keep view clean
            pthread_mutex_lock(&g_emit_mutex);
            emit(g_mfd, EV_REL, REL_X, 3000);
            emit(g_mfd, EV_REL, REL_Y, 3000);
            emit(g_mfd, EV_SYN, SYN_REPORT, 0);
            pthread_mutex_unlock(&g_emit_mutex);
        }
    }
    fflush(stdout);

    // 3. Start background IR worker thread for LG TV remote
    pthread_t ir_tid;
    if (pthread_create(&ir_tid, NULL, ir_worker_thread, NULL) == 0) {
        pthread_detach(ir_tid);
    } else {
        perror("pthread_create ir_worker_thread");
    }

    // 4. UDP server on port 8766 (for Web / Phone Remote)
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
                pthread_mutex_lock(&g_emit_mutex);
                emit(g_kfd, EV_KEY, key, 1);
                emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                pthread_mutex_unlock(&g_emit_mutex);
            }
        } else if (cmd == 'U') {
            int key = 0;
            if (sscanf(buf + 1, "%d", &key) == 1 && key > 0) {
                pthread_mutex_lock(&g_emit_mutex);
                emit(g_kfd, EV_KEY, key, 0);
                emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                pthread_mutex_unlock(&g_emit_mutex);
            }
        } else if (cmd == 'K') {
            int key = 0;
            if (sscanf(buf + 1, "%d", &key) == 1 && key > 0) {
                pthread_mutex_lock(&g_emit_mutex);
                emit(g_kfd, EV_KEY, key, 1);
                emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                usleep(25000);
                emit(g_kfd, EV_KEY, key, 0);
                emit(g_kfd, EV_SYN, SYN_REPORT, 0);
                pthread_mutex_unlock(&g_emit_mutex);
            }
        } else if (cmd == 'C') {
            // Confirm/OK action: ALWAYS emit KEY_ENTER (28) on pure keyboard, NEVER a mouse click!
            pthread_mutex_lock(&g_emit_mutex);
            emit(g_kfd, EV_KEY, 28, 1);
            emit(g_kfd, EV_SYN, SYN_REPORT, 0);
            usleep(25000);
            emit(g_kfd, EV_KEY, 28, 0);
            emit(g_kfd, EV_SYN, SYN_REPORT, 0);
            pthread_mutex_unlock(&g_emit_mutex);
        } else if (cmd == 'M' && g_mfd >= 0) {
            int dx = 0, dy = 0;
            if (sscanf(buf + 1, "%d %d", &dx, &dy) >= 1) {
                pthread_mutex_lock(&g_emit_mutex);
                if (dx != 0) emit(g_mfd, EV_REL, REL_X, dx);
                if (dy != 0) emit(g_mfd, EV_REL, REL_Y, dy);
                emit(g_mfd, EV_SYN, SYN_REPORT, 0);
                pthread_mutex_unlock(&g_emit_mutex);
            }
        } else if (cmd == 'W' && g_mfd >= 0) {
            int w = 0;
            if (sscanf(buf + 1, "%d", &w) == 1) {
                pthread_mutex_lock(&g_emit_mutex);
                emit(g_mfd, EV_REL, REL_WHEEL, w);
                emit(g_mfd, EV_SYN, SYN_REPORT, 0);
                pthread_mutex_unlock(&g_emit_mutex);
            }
        }
    }

    ioctl(g_kfd, UI_DEV_DESTROY);
    close(g_kfd);
    if (g_mfd >= 0) {
        ioctl(g_mfd, UI_DEV_DESTROY);
        close(g_mfd);
    }
    close(sfd);
    return 0;
}
