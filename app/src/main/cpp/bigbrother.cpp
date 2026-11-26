#include <jni.h>
#include <opencv2/opencv.hpp>
#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <vector>

namespace {

    constexpr int kTargetSize = 640;
    constexpr int kChannels = 3;
    constexpr float kConfThreshold = 0.50f;
    constexpr float kNmsThreshold = 0.45f;

    Ort::Env g_env{ORT_LOGGING_LEVEL_WARNING, "BigBrotherYolo"};
    Ort::MemoryInfo g_memoryInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

    std::mutex g_sessionMutex;
    std::shared_ptr<Ort::Session> g_session;

    std::vector<uint8_t> g_i420Buffer;
    std::vector<float> g_inputTensorValues(
            static_cast<size_t>(kChannels) * kTargetSize * kTargetSize,
            0.0f
    );

    std::mutex g_boxesMutex;
    std::vector<float> g_latestBoxes;
    int64_t g_latestFrameTimestampNs = 0;

    struct Detection {
        float x1, y1, x2, y2, score;
        int classId;
    };

    class JniUtfCharsGuard {
    public:
        JniUtfCharsGuard(JNIEnv* env, jstring str) : env_(env), str_(str) {
            chars_ = (env_ && str_) ? env_->GetStringUTFChars(str_, nullptr) : nullptr;
        }
        ~JniUtfCharsGuard() {
            if (env_ && str_ && chars_) {
                env_->ReleaseStringUTFChars(str_, chars_);
            }
        }
        JniUtfCharsGuard(const JniUtfCharsGuard&) = delete;
        JniUtfCharsGuard& operator=(const JniUtfCharsGuard&) = delete;

        const char* get() const { return chars_; }

    private:
        JNIEnv* env_;
        jstring str_;
        const char* chars_ = nullptr;
    };

    bool isFiniteBox(float x1, float y1, float x2, float y2, float score) {
        return std::isfinite(x1) && std::isfinite(y1) &&
               std::isfinite(x2) && std::isfinite(y2) &&
               std::isfinite(score);
    }

    bool hasEnoughCapacity(JNIEnv* env, jobject buffer, int rowStride, int pixelStride, int rows, int width) {
        if (!env || !buffer || rows <= 0 || width <= 0 || pixelStride <= 0) {
            return false;
        }

        const jlong cap = env->GetDirectBufferCapacity(buffer);
        if (cap <= 0) {
            return false;
        }

        const int64_t stride64 = static_cast<int64_t>(rowStride);
        const int64_t rows64 = static_cast<int64_t>(rows);
        const int64_t minRowBytes64 = static_cast<int64_t>(width) * static_cast<int64_t>(pixelStride);

        if (minRowBytes64 <= 0) {
            return false;
        }

        const int64_t rowSpan = stride64 * (rows64 - 1);
        const int64_t minOffset = std::min<int64_t>(0, rowSpan);
        const int64_t maxOffset = std::max<int64_t>(minRowBytes64, rowSpan + minRowBytes64);
        const int64_t requiredBytes = maxOffset - minOffset;

        return requiredBytes > 0 && static_cast<int64_t>(cap) >= requiredBytes;
    }

    float computeIoU(const Detection& a, const Detection& b) {
        const float interX1 = std::max(a.x1, b.x1);
        const float interY1 = std::max(a.y1, b.y1);
        const float interX2 = std::min(a.x2, b.x2);
        const float interY2 = std::min(a.y2, b.y2);

        const float interW = std::max(0.0f, interX2 - interX1);
        const float interH = std::max(0.0f, interY2 - interY1);
        const float interArea = interW * interH;

        const float areaA = std::max(0.0f, a.x2 - a.x1) * std::max(0.0f, a.y2 - a.y1);
        const float areaB = std::max(0.0f, b.x2 - b.x1) * std::max(0.0f, b.y2 - b.y1);
        const float unionArea = areaA + areaB - interArea;

        return (unionArea > 0.0f) ? (interArea / unionArea) : 0.0f;
    }

    std::vector<Detection> applyNMS(std::vector<Detection>& boxes, float iouThreshold) {
        std::sort(boxes.begin(), boxes.end(), [](const Detection& a, const Detection& b) {
            return a.score > b.score;
        });

        std::vector<Detection> kept;
        kept.reserve(boxes.size());
        std::vector<bool> suppressed(boxes.size(), false);

        for (size_t i = 0; i < boxes.size(); ++i) {
            if (suppressed[i]) continue;
            kept.push_back(boxes[i]);

            for (size_t j = i + 1; j < boxes.size(); ++j) {
                if (suppressed[j] || boxes[i].classId != boxes[j].classId) continue;
                if (computeIoU(boxes[i], boxes[j]) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    bool buildI420Buffer(
            const uint8_t* yPtr, int rowStrideY, int pixelStrideY,
            const uint8_t* uPtr, int rowStrideU, int pixelStrideU,
            const uint8_t* vPtr, int rowStrideV, int pixelStrideV,
            int width, int height,
            std::vector<uint8_t>& i420Out
    ) {
        if (!yPtr || !uPtr || !vPtr || width <= 0 || height <= 0 ||
            pixelStrideY <= 0 || pixelStrideU <= 0 || pixelStrideV <= 0) {
            return false;
        }

        const int chromaW = width / 2;
        const int chromaH = height / 2;
        const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
        const size_t uvSize = static_cast<size_t>(chromaW) * static_cast<size_t>(chromaH);

        i420Out.resize(ySize + uvSize * 2);
        uint8_t* dstY = i420Out.data();
        uint8_t* dstU = dstY + ySize;
        uint8_t* dstV = dstU + uvSize;

        for (int r = 0; r < height; ++r) {
            const uint8_t* srcRow = yPtr + static_cast<int64_t>(r) * static_cast<int64_t>(rowStrideY);
            uint8_t* dstRow = dstY + static_cast<size_t>(r) * static_cast<size_t>(width);
            if (pixelStrideY == 1) {
                std::memcpy(dstRow, srcRow, static_cast<size_t>(width));
            } else {
                for (int c = 0; c < width; ++c) {
                    dstRow[c] = srcRow[static_cast<size_t>(c) * static_cast<size_t>(pixelStrideY)];
                }
            }
        }

        for (int r = 0; r < chromaH; ++r) {
            const uint8_t* srcRowU = uPtr + static_cast<int64_t>(r) * static_cast<int64_t>(rowStrideU);
            const uint8_t* srcRowV = vPtr + static_cast<int64_t>(r) * static_cast<int64_t>(rowStrideV);
            uint8_t* dstRowU = dstU + static_cast<size_t>(r) * static_cast<size_t>(chromaW);
            uint8_t* dstRowV = dstV + static_cast<size_t>(r) * static_cast<size_t>(chromaW);

            if (pixelStrideU == 1 && pixelStrideV == 1) {
                std::memcpy(dstRowU, srcRowU, static_cast<size_t>(chromaW));
                std::memcpy(dstRowV, srcRowV, static_cast<size_t>(chromaW));
            } else {
                for (int c = 0; c < chromaW; ++c) {
                    dstRowU[c] = srcRowU[static_cast<size_t>(c) * static_cast<size_t>(pixelStrideU)];
                    dstRowV[c] = srcRowV[static_cast<size_t>(c) * static_cast<size_t>(pixelStrideV)];
                }
            }
        }

        return true;
    }

    void rotateFrameIfNeeded(cv::Mat& rgb, int rotationDegrees) {
        const int normAngle = ((rotationDegrees % 360) + 360) % 360;
        switch (normAngle) {
            case 90:
                cv::rotate(rgb, rgb, cv::ROTATE_90_CLOCKWISE);
                break;
            case 180:
                cv::rotate(rgb, rgb, cv::ROTATE_180);
                break;
            case 270:
                cv::rotate(rgb, rgb, cv::ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                break;
        }
    }

    cv::Mat makeLetterbox(const cv::Mat& rgb, int targetSize, float& scale, int& dw, int& dh) {
        const int srcW = rgb.cols;
        const int srcH = rgb.rows;

        scale = std::min(
                static_cast<float>(targetSize) / static_cast<float>(srcW),
                static_cast<float>(targetSize) / static_cast<float>(srcH)
        );

        const int newW = std::clamp(static_cast<int>(std::round(srcW * scale)), 1, targetSize);
        const int newH = std::clamp(static_cast<int>(std::round(srcH * scale)), 1, targetSize);

        dw = (targetSize - newW) / 2;
        dh = (targetSize - newH) / 2;

        cv::Mat resized;
        cv::resize(rgb, resized, cv::Size(newW, newH), 0.0, 0.0, cv::INTER_LINEAR);

        cv::Mat canvas(targetSize, targetSize, CV_8UC3, cv::Scalar(114, 114, 114));
        resized.copyTo(canvas(cv::Rect(dw, dh, newW, newH)));
        return canvas;
    }

    void fillNchwTensor(const cv::Mat& rgb640, std::vector<float>& dst) {
        constexpr float kInv255 = 1.0f / 255.0f;
        constexpr size_t kPlaneSize = static_cast<size_t>(kTargetSize) * kTargetSize;

        float* chR = dst.data();
        float* chG = chR + kPlaneSize;
        float* chB = chG + kPlaneSize;

        for (int y = 0; y < kTargetSize; ++y) {
            const auto* row = rgb640.ptr<const uint8_t>(y);
            const size_t rowOffset = static_cast<size_t>(y) * kTargetSize;
            for (int x = 0; x < kTargetSize; ++x) {
                const size_t px = static_cast<size_t>(x) * 3;
                const size_t idx = rowOffset + static_cast<size_t>(x);
                chR[idx] = static_cast<float>(row[px + 0]) * kInv255;
                chG[idx] = static_cast<float>(row[px + 1]) * kInv255;
                chB[idx] = static_cast<float>(row[px + 2]) * kInv255;
            }
        }
    }

    void storeLatestBoxes(const std::vector<Detection>& detections, int64_t timestampNs) {
        std::vector<float> packed;
        packed.reserve(detections.size() * 6);
        for (const auto& det : detections) {
            packed.push_back(det.x1);
            packed.push_back(det.y1);
            packed.push_back(det.x2);
            packed.push_back(det.y2);
            packed.push_back(det.score);
            packed.push_back(static_cast<float>(det.classId));
        }

        std::lock_guard<std::mutex> lock(g_boxesMutex);
        g_latestBoxes.swap(packed);
        g_latestFrameTimestampNs = timestampNs;
    }

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bigbrother_data_jni_NativeFrameBridge_nativeInitModel(
        JNIEnv* env,
        jobject,
        jstring modelPath
) {
    JniUtfCharsGuard pathGuard(env, modelPath);
    const char* pathChars = pathGuard.get();
    if (!pathChars || pathChars[0] == '\0') {
        return JNI_FALSE;
    }

    Ort::SessionOptions sessionOptions;
    sessionOptions.SetIntraOpNumThreads(4);
    sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    try {
        auto newSession = std::make_shared<Ort::Session>(g_env, pathChars, sessionOptions);
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_session = std::move(newSession);
        return JNI_TRUE;
    } catch (const Ort::Exception&) {
        return JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_bigbrother_data_jni_NativeFrameBridge_nativeSubmitFrame(
        JNIEnv* env,
        jobject,
        jobject plane0,
        jint rowStride0,
        jint pixelStride0,
        jobject plane1,
        jint rowStride1,
        jint pixelStride1,
        jobject plane2,
        jint rowStride2,
        jint pixelStride2,
        jint width,
        jint height,
        jint rotationDegrees,
        jlong timestampNs
) {
    std::shared_ptr<Ort::Session> session;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        session = g_session;
    }
    if (!session) {
        return -1;
    }

    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
        return -2;
    }

    const int chromaW = width / 2;
    const int chromaH = height / 2;

    if (!hasEnoughCapacity(env, plane0, rowStride0, pixelStride0, height, width) ||
        !hasEnoughCapacity(env, plane1, rowStride1, pixelStride1, chromaH, chromaW) ||
        !hasEnoughCapacity(env, plane2, rowStride2, pixelStride2, chromaH, chromaW)) {
        return -3;
    }

    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(plane0));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(plane1));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(plane2));

    if (!yPtr || !uPtr || !vPtr) {
        return -4;
    }

    if (!buildI420Buffer(
            yPtr, rowStride0, pixelStride0,
            uPtr, rowStride1, pixelStride1,
            vPtr, rowStride2, pixelStride2,
            width, height, g_i420Buffer)) {
        return -5;
    }

    cv::Mat yuvMat(height + chromaH, width, CV_8UC1, g_i420Buffer.data());
    cv::Mat rgbMat;
    cv::cvtColor(yuvMat, rgbMat, cv::COLOR_YUV2RGB_I420);

    rotateFrameIfNeeded(rgbMat, rotationDegrees);

    float scale = 1.0f;
    int dw = 0;
    int dh = 0;
    cv::Mat letterboxed = makeLetterbox(rgbMat, kTargetSize, scale, dw, dh);

    fillNchwTensor(letterboxed, g_inputTensorValues);

    const std::vector<int64_t> inputShape = {1, kChannels, kTargetSize, kTargetSize};
    Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
            g_memoryInfo,
            g_inputTensorValues.data(),
            g_inputTensorValues.size(),
            inputShape.data(),
            inputShape.size()
    );

    const char* inputNames[] = {"images"};
    const char* outputNames[] = {"output0"};

    std::vector<Ort::Value> outputTensors;
    try {
        outputTensors = session->Run(
                Ort::RunOptions{nullptr},
                inputNames, &inputTensor, 1,
                outputNames, 1
        );
    } catch (const Ort::Exception&) {
        return -8;
    } catch (const std::exception&) {
        return -9;
    }

    if (outputTensors.empty() || !outputTensors[0].IsTensor()) {
        return -6;
    }

    const float* rawOutput = outputTensors[0].GetTensorData<float>();
    const auto shape = outputTensors[0].GetTensorTypeAndShapeInfo().GetShape();
    if (!rawOutput || shape.size() < 3) {
        return -6;
    }

    const int64_t numChannels64 = shape[1];
    const int64_t numAnchors64 = shape[2];
    if (numChannels64 <= 4 || numAnchors64 <= 0 ||
        numChannels64 > std::numeric_limits<int>::max() ||
        numAnchors64 > std::numeric_limits<int>::max()) {
        return -7;
    }

    const int numChannels = static_cast<int>(numChannels64);
    const int numAnchors = static_cast<int>(numAnchors64);

    const float maxW = static_cast<float>(rgbMat.cols);
    const float maxH = static_cast<float>(rgbMat.rows);
    const size_t stride = static_cast<size_t>(numAnchors);

    std::vector<Detection> rawDetections;
    for (int i = 0; i < numAnchors; ++i) {
        const size_t idx = static_cast<size_t>(i);
        float maxScore = 0.0f;
        int bestClass = -1;

        for (int c = 4; c < numChannels; ++c) {
            const float logit = rawOutput[static_cast<size_t>(c) * stride + idx];
            if (!std::isfinite(logit)) {
                continue;
            }
            // Модель отдаёт class logits: переводим в вероятность (sigmoid),
            // чтобы порог 0.5 и число в UI имели смысл вероятности.
            const float probability = 1.0f / (1.0f + std::exp(-logit));
            if (probability > maxScore) {
                maxScore = probability;
                bestClass = c - 4;
            }
        }

        if (bestClass >= 0 && maxScore > kConfThreshold) {
            const float cx = rawOutput[0 * stride + idx];
            const float cy = rawOutput[1 * stride + idx];
            const float w  = rawOutput[2 * stride + idx];
            const float h  = rawOutput[3 * stride + idx];

            const float x1 = cx - w * 0.5f;
            const float y1 = cy - h * 0.5f;
            const float x2 = cx + w * 0.5f;
            const float y2 = cy + h * 0.5f;

            if (!isFiniteBox(x1, y1, x2, y2, maxScore)) {
                continue;
            }

            const float origX1 = std::clamp((x1 - static_cast<float>(dw)) / scale, 0.0f, maxW);
            const float origY1 = std::clamp((y1 - static_cast<float>(dh)) / scale, 0.0f, maxH);
            const float origX2 = std::clamp((x2 - static_cast<float>(dw)) / scale, 0.0f, maxW);
            const float origY2 = std::clamp((y2 - static_cast<float>(dh)) / scale, 0.0f, maxH);

            rawDetections.push_back({origX1, origY1, origX2, origY2, maxScore, bestClass});
        }
    }

    std::vector<Detection> finalDetections = applyNMS(rawDetections, kNmsThreshold);
    storeLatestBoxes(finalDetections, static_cast<int64_t>(timestampNs));

    return static_cast<jint>(finalDetections.size());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_bigbrother_data_jni_NativeFrameBridge_nativeCopyLatestDetections(
        JNIEnv* env,
        jobject
) {
    std::vector<float> snapshot;
    {
        std::lock_guard<std::mutex> lock(g_boxesMutex);
        snapshot = g_latestBoxes;
    }

    if (snapshot.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }

    const jsize count = static_cast<jsize>(snapshot.size());
    jfloatArray out = env->NewFloatArray(count);
    if (out != nullptr && count > 0) {
        env->SetFloatArrayRegion(out, 0, count, snapshot.data());
    }
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_bigbrother_data_jni_NativeFrameBridge_nativeGetLatestFrameTimestampNs(
        JNIEnv*,
        jobject
) {
    std::lock_guard<std::mutex> lock(g_boxesMutex);
    return static_cast<jlong>(g_latestFrameTimestampNs);
}