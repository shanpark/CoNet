package io.github.shanpark.conet.util

fun log(str: String) {
    println("${Thread.currentThread().name}\t- $str")
}