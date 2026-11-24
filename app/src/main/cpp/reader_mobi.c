#include <jni.h>
#include <limits.h>
#include <stdio.h>
#include <mobi.h>

static int write_parts(JNIEnv *env, jobject output, jmethodID write,
                       const MOBIPart *part, const char *prefix) {
    for (; part != NULL; part = part->next) {
        if (!part->size) continue;
        if (part->size > 32 * 1024 * 1024) return 0;
        const MOBIFileMeta type = mobi_get_filemeta_by_type(part->type);
        char path[160];
        if (part->type == T_OPF) {
            snprintf(path, sizeof(path), "OEBPS/content.opf");
        } else {
            snprintf(path, sizeof(path), "OEBPS/%s%05zu.%s", prefix, part->uid, type.extension);
        }
        jstring name = (*env)->NewStringUTF(env, path);
        jbyteArray data = (*env)->NewByteArray(env, (jsize)part->size);
        if (name == NULL || data == NULL) return 0;
        (*env)->SetByteArrayRegion(env, data, 0, (jsize)part->size, (const jbyte *)part->data);
        if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, output, write, name, data);
        (*env)->DeleteLocalRef(env, name);
        (*env)->DeleteLocalRef(env, data);
        if ((*env)->ExceptionCheck(env)) return 0;
    }
    return 1;
}

JNIEXPORT jint JNICALL Java_tw_boox_ereading_Mobi_writeBook(
    JNIEnv *env, jclass unused, jstring filename, jobject output) {
    (void)unused;
    jmethodID write = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, output),
        "write", "(Ljava/lang/String;[B)V");
    if (write == NULL) return 1;

    const char *path = (*env)->GetStringUTFChars(env, filename, NULL);
    if (path == NULL) return 1;
    MOBIData *book = mobi_init();
    MOBI_RET status = book == NULL ? MOBI_ERROR : mobi_load_filename(book, path);
    (*env)->ReleaseStringUTFChars(env, filename, path);
    if (book == NULL) return 1;

    jint result = 1;
    MOBIRawml *raw = NULL;
    if (status == MOBI_FILE_ENCRYPTED || (status == MOBI_SUCCESS && mobi_is_encrypted(book))) {
        result = 2;
    } else if (status == MOBI_SUCCESS && !mobi_is_replica(book)) {
        raw = mobi_init_rawml(book);
        if (raw != NULL && mobi_parse_rawml(raw, book) == MOBI_SUCCESS
            && raw->markup != NULL
            && write_parts(env, output, write, raw->markup, "part")
            && write_parts(env, output, write, raw->flow == NULL ? NULL : raw->flow->next, "flow")
            && write_parts(env, output, write, raw->resources, "resource")) {
            result = 0;
        }
    }
    if (raw != NULL) mobi_free_rawml(raw);
    mobi_free(book);
    return result;
}
