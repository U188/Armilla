package io.github.mangi.eta.agent.device

/**
 * KernelSU / Magisk 默认把 `su` 留在调用方的隔离 mount namespace。
 * 该 namespace 会把 `/data/data`、`/data/user` 换成只含本应用与 GMS 的 tmpfs，
 * 终端和文件工具即使用 root 也看不到其他应用私有目录。
 *
 * `-M` / `--mount-master` 进入 init 的全局挂载命名空间。
 */
internal object RootSu {
    fun args(script: String): Array<String> = arrayOf("su", "-M", "-c", script)

    fun process(script: String): ProcessBuilder = ProcessBuilder(*args(script))
}
