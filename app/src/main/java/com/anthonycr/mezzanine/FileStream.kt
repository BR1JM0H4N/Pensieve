package com.anthonycr.mezzanine

/**
 * Drop-in stub replacing the mezzanine kapt library.
 * The annotation is kept so existing source files compile unchanged,
 * but it is no longer processed — implementations are in MezzanineGenerator.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class FileStream(val value: String)
