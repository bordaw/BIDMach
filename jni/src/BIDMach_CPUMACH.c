#include <jni.h>
#include <mkl.h>
#include <omp.h>
#include <math.h>

JNIEXPORT void JNICALL Java_edu_berkeley_bid_CPUMACH_word2vecConv
(JNIEnv *env, jobject obj, jint nrows, jint ncols, const jint skip, jintArray jW, jfloatArray jA, jfloatArray jB, jfloat lrate)
{
  int i, j, k, c, ia, ib, coff;
  float cv;
  int * W = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWA, JNI_FALSE));
  float * A = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jA, JNI_FALSE));
  float * B = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jB, JNI_FALSE));
  float * C, * Btmp;

  C = (float *)malloc((2*skip+1)*sizeof(float));
  Btmp = (float *)malloc(nrows*sizeof(float));  

#pragma omp parallel for
  for (i = 0; i < ncols; i++) {
    ib = W[i];
    for (j = -skip; j <= skip; j++) {
      if (j != 0 && i + j >= 0 && i + j < ncols) {
        ia = W[i + j];
        cv = 0;
        for (c = 0; c < nrows; c++) {
          cv += A[c + ia] * B[c + ib];
        }

        if (cv > 16.0f) {
          cv = 1.0f;
        } else if (cv < -16.0f) {
          cv = 0.0f;
        } else {
          cv = exp(cv);
          cv = 1.0f / (1.0f + cv);
        }

        C[j + skip] = 1.0f - cv;
      }
    } 

    for (j = -skip; j <= skip; j++) {
      ib = W[i];
      for (c = 0; c < nrows; c++) {
        Btmp[c] = 0;
      }
      if (j != 0 && i + j >= 0 && i + j < ncols) {
        ia = W[i + j];
        cv = lrate * C[j + skip];
        for (c = 0; c < nrows; c++) {
          Btmp[c] += cv * A[c + ia];
          A[c + ia] += cv * B[c + ib];
        }
      }
      for (c = 0; c < nrows; c++) {
        B[c + ib] += Btmp[c];
      }
    }
  }

  free(Btmp);
  free(C);
  (*env)->ReleasePrimitiveArrayCritical(env, jB, B, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jA, A, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jW, W, 0);
}

JNIEXPORT void JNICALL Java_edu_berkeley_bid_CPUMACH_word2vec
(JNIEnv *env, jobject obj, jint nrows, jint ncols, const jint nwa, const jint nwb, jintArray jWA, jintArray jWB, 
 jfloatArray jA, jfloatArray jB, jfloat lrate)
{
  int i, j, k, c, ia, ib, coff;
  float cv;
  int * WA = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWA, JNI_FALSE));
  int * WB = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWB, JNI_FALSE));
  float * A = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jA, JNI_FALSE));
  float * B = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jB, JNI_FALSE));
  float * C;

  C = (float *)malloc(nwa*nwb*sizeof(float));
  

#pragma omp parallel for
  for (i = 0; i < ncols; i++) {
    for (j = 0; j < nwa; j++) {
      ia = nrows*WA[j+i*nwa];
      for (k = 0; k < nwb; k++) {
        ib = nrows*WB[k+i*nwb];
        cv = 0;
        for (c = 0; c < nrows; c++) {
          cv += A[c + ia] * B[c + ib];
        }

        if (cv > 16.0f) {
          cv = 1.0f;
        } else if (cv < -16.0f) {
          cv = 0.0f;
        } else {
          cv = exp(cv);
          cv = 1.0f / (1.0f + cv);
        }

        C[j + k * nwa] = -cv;
      }
    } 

    for (j = 0; j < nwa; j++) {
      ia = nrows*WA[j+i*nwa];
      for (k = 0; k < nwb; k++) {
        ib = nrows*WB[k+i*nwb];
        cv = lrate * C[j + nwa * k];
        for (c = 0; c < nrows; c++) {
          A[c + ia] += cv * B[c + ib];
        }
      }
    }

    for (k = 0; k < nwb; k++) {
      ib = nrows*WB[k+i*nwb];
      for (j = 0; j < nwa; j++) {
        ia = nrows*WA[j+i*nwa];
        cv = lrate * C[j + nwa * k];
        for (c = 0; c < nrows; c++) {
          B[c + ib] += cv * A[c + ia];
        }
      }
    }
  }

  free(C);
  (*env)->ReleasePrimitiveArrayCritical(env, jB, B, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jA, A, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jWB, WB, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jWA, WA, 0);
}

JNIEXPORT void JNICALL Java_edu_berkeley_bid_CPUMACH_word2vecFwd
(JNIEnv *env, jobject obj, jint nrows, jint ncols, const jint nwa, const jint nwb, jintArray jWA, jintArray jWB, 
 jfloatArray jA, jfloatArray jB, jfloatArray jC)
{
  int i, j, k, c, ia, ib, coff;
  float sum;
  jint * WA = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWA, JNI_FALSE));
  jint * WB = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWB, JNI_FALSE));
  jfloat * A = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jA, JNI_FALSE));
  jfloat * B = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jB, JNI_FALSE));
  jfloat * C = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jC, JNI_FALSE));

#pragma omp parallel for
  for (i = 0; i < ncols; i++) {
    for (j = 0; j < nwa; j++) {
      ia = nrows*WA[j+i*nwa];
      for (k = 0; k < nwb; k++) {
        ib = nrows*WB[k+i*nwb];
        sum = 0;
        for (c = 0; c < nrows; c++) {
          sum += A[c + ia] * B[c + ib];
        }
        coff = nwa * (k + nwb * i);
        C[j + coff] = sum;
      }
    } 
  }

  (*env)->ReleasePrimitiveArrayCritical(env, jC, C, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jB, B, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jA, A, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jWB, WB, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jWA, WA, 0);
}

JNIEXPORT void JNICALL Java_edu_berkeley_bid_CPUMACH_word2vecBwd
(JNIEnv *env, jobject obj, jint nrows, jint ncols, jint nwa, jint nwb, jintArray jWA, jintArray jWB, 
 jfloatArray jA, jfloatArray jB, jfloatArray jC, jfloat lrate)
{
  int i, j, k, c;
  float cv;
  int ia, ib;
  jint * WA = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWA, JNI_FALSE));
  jint * WB = (jint *)((*env)->GetPrimitiveArrayCritical(env, jWB, JNI_FALSE));
  jfloat * A = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jA, JNI_FALSE));
  jfloat * B = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jB, JNI_FALSE));
  jfloat * C = (jfloat *)((*env)->GetPrimitiveArrayCritical(env, jC, JNI_FALSE));

#pragma omp parallel for
  for (i = 0; i < ncols; i++) {
    for (j = 0; j < nwa; j++) {
      ia = nrows*WA[j+i*nwa];
      for (c = 0; c < nrows; c++) {
        A[c + ia] = 0;
      }
      for (k = 0; k < nwb; k++) {
        ib = nrows*WB[k+i*nwb];
        cv = lrate * C[j + nwa * (k + nwb * i)];
        for (c = 0; c < nrows; c++) {
          A[c + ia] += cv * B[c + ib];
        }
      }
    }
    for (k = 0; k < nwb; k++) {
      ib = nrows*WB[k+i*nwb];
      for (c = 0; c < nrows; c++) {
        B[c + ib] = 0;
      }
      for (j = 0; j < nwa; j++) {
        ia = nrows*WA[j+i*nwa];
        cv = lrate * C[j + nwa * (k + nwb * i)];
        for (c = 0; c < nrows; c++) {
          B[c + ib] += cv * A[c + ia];
        }
      }
    }
  }
  (*env)->ReleasePrimitiveArrayCritical(env, jC, C, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jB, B, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jA, A, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jWB, WB, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, jWA, WA, 0);
}
