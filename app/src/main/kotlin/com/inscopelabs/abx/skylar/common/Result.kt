package com.inscopelabs.abx.skylar.common

/**
 * Generic result wrapper for Skylar Gateway operations.
 * Discriminated union capturing success with data or failure with error details.
 */
sealed class Result<out T> {

    data class Success<out T>(val data: T) : Result<T>()

    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val errorCode: String? = null
    ) : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun getOrDefault(defaultValue: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Error -> defaultValue
    }

    inline fun onSuccess(action: (value: T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (error: Error) -> Unit): Result<T> {
        if (this is Error) action(this)
        return this
    }

    inline fun <R> map(transform: (value: T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
}
