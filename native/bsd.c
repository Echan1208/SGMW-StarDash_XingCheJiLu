/* Side-rear object detection for StarDash: NanoDet-Plus-m 320 run by ncnn (libncnn.so, C API).
 * No libc dependency of its own; ncnn brings what it needs. JNI slot 230 is GetDirectBufferAddress. */
/* The few ncnn C API calls used here (ncnn/c_api.h, 20261020), declared directly because its headers
 * pull in libc headers that this freestanding build does not have. */
typedef unsigned long size_t;
typedef struct __ncnn_net_t *ncnn_net_t;
typedef struct __ncnn_option_t *ncnn_option_t;
typedef struct __ncnn_mat_t *ncnn_mat_t;
typedef struct __ncnn_extractor_t *ncnn_extractor_t;
typedef struct __ncnn_allocator_t *ncnn_allocator_t;
ncnn_option_t ncnn_option_create(void);
void ncnn_option_set_num_threads(ncnn_option_t opt, int num_threads);
void ncnn_option_set_use_vulkan_compute(ncnn_option_t opt, int enable);
void ncnn_option_set_use_fp16_storage(ncnn_option_t opt, int enable);
void ncnn_option_set_use_fp16_packed(ncnn_option_t opt, int enable);
void ncnn_option_set_use_fp16_arithmetic(ncnn_option_t opt, int enable);
ncnn_net_t ncnn_net_create(void);
void ncnn_net_set_option(ncnn_net_t net, ncnn_option_t opt);
int ncnn_net_load_param_memory(ncnn_net_t net, const char *mem);
size_t ncnn_net_load_model_memory(ncnn_net_t net, const unsigned char *mem);
ncnn_mat_t ncnn_mat_from_pixels_resize(const unsigned char *pixels, int type, int w, int h, int stride,
                                       int target_width, int target_height, ncnn_allocator_t allocator);
void ncnn_mat_substract_mean_normalize(ncnn_mat_t mat, const float *mean_vals, const float *norm_vals);
int ncnn_mat_get_w(const ncnn_mat_t mat);
int ncnn_mat_get_h(const ncnn_mat_t mat);
void *ncnn_mat_get_data(const ncnn_mat_t mat);
void ncnn_mat_destroy(ncnn_mat_t mat);
ncnn_extractor_t ncnn_extractor_create(ncnn_net_t net);
int ncnn_extractor_input(ncnn_extractor_t ex, const char *name, const ncnn_mat_t mat);
int ncnn_extractor_extract(ncnn_extractor_t ex, const char *name, ncnn_mat_t *mat);
void ncnn_extractor_destroy(ncnn_extractor_t ex);
#define NCNN_MAT_PIXEL_BGR 2
#define NCNN_MAT_PIXEL_RGBA 4
#define NCNN_MAT_PIXEL_X2Y(X, Y) (X | (Y << 16))
/* C++ helpers from ncnn/cpu.h, called by their exported names: ncnn::set_cpu_powersave(int) and
 * ncnn::set_kmp_blocktime(int). */
int _ZN4ncnn17set_cpu_powersaveEi(int powersave);
void _ZN4ncnn17set_kmp_blocktimeEi(int time_ms);

typedef const void *const *EnvTable;
typedef EnvTable *Env;
typedef void *(*DirectAddr)(Env, void *);
#define ADDR(env, buf) ((void *)((DirectAddr)(*(env))[230])((env), (buf)))

enum { IN = 320, CLASSES = 80, REG = 8, ROW = 112 };

static ncnn_net_t net;
static ncnn_option_t opt;

/* Classes that matter beside the car: person, bicycle, car, motorcycle, bus, truck. */
static const int WANTED[] = {0, 1, 2, 3, 5, 7};

/* e^x for x <= 0 (softmax after subtracting the maximum); about 1e-4 relative error. */
static float exp_neg(float x) {
    if (x < -30.f) return 0.f;
    float t = x * 1.44269504f; /* to base 2 */
    int i = (int)t;
    if ((float)i > t) i--;     /* floor */
    float f = t - (float)i;    /* 0 <= f < 1 */
    float p = 1.f + f * (0.69314718f + f * (0.24022650f + f * (0.05550411f + f * (0.00961813f + f * 0.00133336f))));
    if (i < -126) return 0.f;
    union { float v; int b; } u;
    u.b = (i + 127) << 23;     /* 2^i */
    return p * u.v;
}

/* Loads the network from memory. The param text must end with a 0 byte; the weights buffer must stay
 * alive as long as the network (ncnn reads it in place). Returns 0 on success. */
int Java_com_sgmwstarlight_stardash_Detector_load(Env env, void *cls, void *paramBuf, void *binBuf, int threads,
                                                  int flags) {
    if (net) return 0;
    /* Big cores only (the small ones made one look take about 170 ms), and let the worker threads
     * sleep as soon as a layer is done instead of spinning for 200 ms between looks. Must run on the
     * thread that will call run(), which is why the app keeps that thread for the whole process. */
    _ZN4ncnn17set_cpu_powersaveEi(2);
    _ZN4ncnn17set_kmp_blocktimeEi(0);
    opt = ncnn_option_create();
    ncnn_option_set_num_threads(opt, threads);
    ncnn_option_set_use_vulkan_compute(opt, 0);
    /* The head unit's A73 cores have no fp16 arithmetic; keep everything in fp32 (bit 0 of flags
     * turns fp16 storage back on, for comparing). */
    ncnn_option_set_use_fp16_storage(opt, flags & 1);
    ncnn_option_set_use_fp16_packed(opt, flags & 1);
    ncnn_option_set_use_fp16_arithmetic(opt, 0);
    net = ncnn_net_create();
    ncnn_net_set_option(net, opt);
    if (ncnn_net_load_param_memory(net, (const char *)ADDR(env, paramBuf)) != 0) return -1;
    if (ncnn_net_load_model_memory(net, (const unsigned char *)ADDR(env, binBuf)) == 0) return -2;
    return 0;
}

/* Detects in an RGBA image of w x h. Writes up to maxBoxes rows of {x1, y1, x2, y2, score, class} in
 * image pixels to out (floats), for the wanted classes scoring at least thr. Returns the count
 * (before non-maximum suppression), or -1 if the network is not loaded. */
int Java_com_sgmwstarlight_stardash_Detector_run(Env env, void *cls, void *rgbaBuf, int w, int h, void *outBuf,
                                                 int maxBoxes, float thr) {
    if (!net) return -1;
    const unsigned char *rgba = (const unsigned char *)ADDR(env, rgbaBuf);
    float *out = (float *)ADDR(env, outBuf);
    static const float mean[3] = {103.53f, 116.28f, 123.675f};
    static const float norm[3] = {1.f / 57.375f, 1.f / 57.12f, 1.f / 58.395f};
    ncnn_mat_t in = ncnn_mat_from_pixels_resize(rgba, NCNN_MAT_PIXEL_X2Y(NCNN_MAT_PIXEL_RGBA, NCNN_MAT_PIXEL_BGR),
                                                w, h, w * 4, IN, IN, 0);
    ncnn_mat_substract_mean_normalize(in, mean, norm);
    ncnn_extractor_t ex = ncnn_extractor_create(net);
    ncnn_extractor_input(ex, "in0", in);
    ncnn_mat_t res = 0;
    int n = 0;
    if (ncnn_extractor_extract(ex, "out0", &res) == 0 && ncnn_mat_get_w(res) == ROW) {
        const float *p = (const float *)ncnn_mat_get_data(res);
        int rows = ncnn_mat_get_h(res);
        float sx = (float)w / IN, sy = (float)h / IN;
        int row = 0;
        for (int stride = 8; stride <= 64 && row < rows; stride *= 2) {
            int cells = IN / stride;
            for (int gy = 0; gy < cells; gy++) {
                for (int gx = 0; gx < cells; gx++, row++) {
                    if (row >= rows) break;
                    const float *r = p + (long)row * ROW;
                    int best = -1;
                    float score = thr;
                    for (int k = 0; k < (int)(sizeof WANTED / sizeof WANTED[0]); k++) {
                        if (r[WANTED[k]] >= score) {
                            score = r[WANTED[k]];
                            best = WANTED[k];
                        }
                    }
                    if (best < 0 || n >= maxBoxes) continue;
                    /* Distribution focal loss: each side is the expectation over 8 bins, times the stride. */
                    float dist[4];
                    for (int s = 0; s < 4; s++) {
                        const float *b = r + CLASSES + s * REG;
                        float m = b[0];
                        for (int j = 1; j < REG; j++) if (b[j] > m) m = b[j];
                        float sum = 0.f, acc = 0.f;
                        for (int j = 0; j < REG; j++) {
                            float e = exp_neg(b[j] - m);
                            sum += e;
                            acc += e * (float)j;
                        }
                        dist[s] = acc / sum * (float)stride;
                    }
                    float cx = (float)(gx * stride), cy = (float)(gy * stride);
                    float *o = out + n * 6;
                    o[0] = (cx - dist[0]) * sx;
                    o[1] = (cy - dist[1]) * sy;
                    o[2] = (cx + dist[2]) * sx;
                    o[3] = (cy + dist[3]) * sy;
                    o[4] = score;
                    o[5] = (float)best;
                    n++;
                }
            }
        }
    }
    if (res) ncnn_mat_destroy(res);
    ncnn_extractor_destroy(ex);
    ncnn_mat_destroy(in);
    return n;
}
