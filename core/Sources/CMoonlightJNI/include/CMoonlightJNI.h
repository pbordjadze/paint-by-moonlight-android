#pragma once
// The C ABI MoonlightCore exports (CABI.swift), called by the JNI glue in moonlight_jni.c.
#include <stdint.h>

void mc_free(void *pointer);
uint32_t mc_pipeline_version(void);
void *mc_job_new(void);
void mc_job_free(void *job);
void mc_job_cancel(void *job);
float mc_job_progress(void *job);
int32_t mc_make(void *job, const uint8_t *rgba, int32_t width, int32_t height, int32_t length, const uint8_t *findings,
                int64_t findings_count, uint8_t **template_bytes, int64_t *template_count, uint8_t **settings,
                int64_t *settings_count);
int32_t mc_model_input(const uint8_t *rgba, int32_t width, int32_t height, int32_t model, float **out, int64_t *out_count,
                       int32_t *size);
int32_t mc_model_map(int32_t model, const float *const *outputs, const int32_t *shapes, int32_t count, const int32_t *size,
                     uint8_t **out, int64_t *out_count);
int32_t mc_scene(const uint8_t *bytes, int64_t count, uint64_t nickname_seed, int32_t nickname_version, uint8_t **out,
                 int64_t *out_count);
