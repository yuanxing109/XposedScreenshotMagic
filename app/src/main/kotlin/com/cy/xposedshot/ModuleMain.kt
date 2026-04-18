package com.cy.xposedshot

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class ModuleMain : XposedModule() {
    // 反射查找结果会在系统进程内重复使用，缓存后可以减少窗口频繁创建时的查找成本。
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val zeroArgMethodCache = ConcurrentHashMap<String, Method>()
    // debug/release 状态来自模块自身 APK，注入到 system_server 后依然保持一致。
    private val debugLoggingEnabled by lazy(LazyThreadSafetyMode.NONE) {
        (moduleApplicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        logStage(
            priority = Log.INFO,
            stage = "module",
            message = "Loaded ${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion",
        )
    }

    @SuppressLint("PrivateApi")
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // 模块只在 Android 15+ 的 HyperOS 上启用，其余平台直接退出初始化流程。
        if (!isSupportedPlatform()) {
            return
        }

        val classLoader = param.classLoader
        // SurfaceControl 相关反射句柄在启动阶段准备一次，运行期直接复用。
        val handles = prepareReflectionHandles(classLoader).getOrElse { error ->
            logStage(Log.ERROR, "bootstrap", "Unable to prepare SurfaceControl reflection", error)
            return
        }
        val animatorClass = runCatching {
            Class.forName(WINDOW_STATE_ANIMATOR, false, classLoader)
        }.getOrElse { error ->
            logStage(Log.ERROR, "bootstrap", "Unable to load $WINDOW_STATE_ANIMATOR", error)
            return
        }

        val hookTargets = resolveHookTargets(animatorClass)
        if (hookTargets.isEmpty()) {
            logStage(Log.WARN, "bootstrap", "No suitable $WINDOW_STATE_ANIMATOR.$CREATE_SURFACE_LOCKED methods found")
            return
        }

        hookTargets.forEach { method ->
            method.isAccessible = true
            hook(method).intercept { chain ->
                // 先执行系统原始逻辑，再对已经创建完成的 Surface 补打跳过截图标记。
                chain.proceed().also {
                    chain.thisObject?.let { animator ->
                        applySkipScreenshot(animator, handles)
                    }
                }
            }
        }

        logStage(
            priority = Log.INFO,
            stage = "bootstrap",
            message = "Hooked ${hookTargets.size} $WINDOW_STATE_ANIMATOR.$CREATE_SURFACE_LOCKED method(s)",
        )
    }

    private fun isSupportedPlatform(): Boolean {
        if (Build.VERSION.SDK_INT <= 34) {
            logStage(Log.INFO, "bootstrap", "Android 15+ required, sdk=${Build.VERSION.SDK_INT}")
            return false
        }

        if (readMiOsVersionName().isEmpty()) {
            logStage(Log.INFO, "bootstrap", "HyperOS property ro.mi.os.version.name is missing")
            return false
        }

        return true
    }

    // 构建 Transaction 反射句柄，后续只保留 Method/Constructor 调用，避免重复查找。
    private fun prepareReflectionHandles(classLoader: ClassLoader): Result<ReflectionHandles> =
        runCatching {
            val surfaceControlClass = Class.forName(SURFACE_CONTROL, false, classLoader)
            val transactionClass = Class.forName(SURFACE_CONTROL_TRANSACTION, false, classLoader)

            ReflectionHandles(
                transactionConstructor = transactionClass.getDeclaredConstructor().apply {
                    isAccessible = true
                },
                setFlagsMethod = transactionClass.getDeclaredMethod(
                    "setFlags",
                    surfaceControlClass,
                    Int::class.javaPrimitiveType!!,
                ).apply {
                    isAccessible = true
                },
                applyMethod = requireZeroArgMethod(transactionClass, "apply"),
                closeMethod = requireZeroArgMethod(transactionClass, "close"),
            )
        }

    // HyperOS ROM 版本差异可能带来重载变化，这里先按严格条件筛选，再回退到同名候选。
    private fun resolveHookTargets(animatorClass: Class<*>): List<Method> {
        val baseCandidates = animatorClass.declaredMethods.filter { method ->
            method.declaringClass == animatorClass &&
                method.name == CREATE_SURFACE_LOCKED &&
                !method.isBridge &&
                !method.isSynthetic
        }
        val strictCandidates = baseCandidates.filter(::isPreferredCreateSurfaceHook)
        if (strictCandidates.isNotEmpty()) {
            return strictCandidates
        }

        if (baseCandidates.isNotEmpty()) {
            logStage(
                priority = Log.WARN,
                stage = "bootstrap",
                message = "Strict hook filter found no matches, fallback candidate count=${baseCandidates.size}",
            )
        }
        return baseCandidates
    }

    private fun isPreferredCreateSurfaceHook(method: Method): Boolean =
        method.parameterCount == 0 &&
            (method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType)

    // 读取当前窗口快照，命中隐藏规则时把截图跳过标记写回 Surface。
    private fun applySkipScreenshot(animator: Any, handles: ReflectionHandles) {
        when (val snapshotResult = readWindowSnapshot(animator)) {
            is SnapshotReadResult.Skipped -> {
                logStage(
                    priority = snapshotResult.priority,
                    stage = snapshotResult.stage,
                    message = snapshotResult.message,
                    throwable = snapshotResult.throwable,
                )
            }

            is SnapshotReadResult.Success -> {
                val snapshot = snapshotResult.snapshot
                logStage(
                    priority = Log.INFO,
                    stage = "inspect",
                    message = "Title=[${snapshot.title}] Package=[${snapshot.packageName}] Type=[${snapshot.type}] Mode=[${snapshot.windowingMode}]",
                )

                val decision = decideHide(snapshot)
                val reason = decision.reason ?: return
                logStage(
                    priority = Log.INFO,
                    stage = "decision",
                    message = "Hide title=[${snapshot.title}] package=[${snapshot.packageName}] reason=${reason.code} hideTask=${decision.hideTaskSurface}",
                )

                applyHide(snapshot, decision, handles)
            }
        }
    }

    // 统一收集窗口标题、归属包名、类型、模式和 Surface 句柄，供后续决策复用。
    private fun readWindowSnapshot(animator: Any): SnapshotReadResult {
        val windowState = when (val result = readObjectField(animator, "mWin")) {
            is FieldReadResult.Value -> result.value
            is FieldReadResult.NullValue -> return inspectSkip("${result.ownerClass}.${result.fieldName} is null")
            is FieldReadResult.Missing -> return inspectSkip("${result.ownerClass}.${result.fieldName} is missing")
            is FieldReadResult.Failure -> {
                return inspectSkip("${result.ownerClass}.${result.fieldName} read failed", result.throwable)
            }
        }
        val layoutParams = when (val result = readObjectField(windowState, "mAttrs")) {
            is FieldReadResult.Value -> result.value
            is FieldReadResult.NullValue -> return inspectSkip("${result.ownerClass}.${result.fieldName} is null")
            is FieldReadResult.Missing -> return inspectSkip("${result.ownerClass}.${result.fieldName} is missing")
            is FieldReadResult.Failure -> {
                return inspectSkip("${result.ownerClass}.${result.fieldName} read failed", result.throwable)
            }
        }
        val title = when (val result = invokeZeroArg(layoutParams, "getTitle")) {
            is MethodCallResult.Value -> (result.value as? CharSequence)?.toString().orEmpty()
            is MethodCallResult.NullValue -> ""
            is MethodCallResult.Missing -> return inspectSkip("${result.ownerClass}.${result.methodName}() is missing")
            is MethodCallResult.Failure -> {
                return inspectSkip("${result.ownerClass}.${result.methodName}() failed", result.throwable)
            }
        }
        val packageName = when (val result = invokeZeroArg(windowState, "getOwningPackage")) {
            is MethodCallResult.Value -> result.value as? String ?: ""
            is MethodCallResult.NullValue -> ""
            is MethodCallResult.Missing -> return inspectSkip("${result.ownerClass}.${result.methodName}() is missing")
            is MethodCallResult.Failure -> {
                return inspectSkip("${result.ownerClass}.${result.methodName}() failed", result.throwable)
            }
        }
        val type = when (val result = readLayoutType(layoutParams)) {
            is FieldReadResult.Value -> result.value
            is FieldReadResult.NullValue -> return inspectSkip("${result.ownerClass}.${result.fieldName} is null")
            is FieldReadResult.Missing -> return inspectSkip("${result.ownerClass}.${result.fieldName} is missing")
            is FieldReadResult.Failure -> {
                return inspectSkip("${result.ownerClass}.${result.fieldName} read failed", result.throwable)
            }
        }
        val windowingMode = readWindowingMode(windowState)
        val windowSurface = when (val result = readWindowSurface(windowState, animator)) {
            is FieldReadResult.Value -> result.value
            is FieldReadResult.NullValue -> return inspectSkip("SurfaceControl is null for [$title]")
            is FieldReadResult.Missing -> return inspectSkip("SurfaceControl is missing for [$title]")
            is FieldReadResult.Failure -> return inspectSkip("SurfaceControl read failed for [$title]", result.throwable)
        }
        val taskSurfaceState = readTaskSurface(windowState, title, windowingMode)

        return SnapshotReadResult.Success(
            WindowSnapshot(
                title = title,
                packageName = packageName,
                type = type,
                windowingMode = windowingMode,
                windowSurface = windowSurface,
                taskSurface = (taskSurfaceState as? TaskSurfaceReadResult.Available)?.surface,
                taskSurfaceState = taskSurfaceState,
            ),
        )
    }

    // 窗口模式读取失败时直接回退到普通模式，隐藏逻辑继续执行。
    private fun readWindowingMode(windowState: Any): Int =
        when (val result = invokeZeroArg(windowState, "getWindowingMode")) {
            is MethodCallResult.Value -> result.value as? Int ?: 0
            is MethodCallResult.NullValue -> 0
            is MethodCallResult.Missing -> {
                logStage(Log.WARN, "inspect", "${result.ownerClass}.${result.methodName}() is missing, defaulting mode=0")
                0
            }

            is MethodCallResult.Failure -> {
                logStage(Log.WARN, "inspect", "${result.ownerClass}.${result.methodName}() failed, defaulting mode=0", result.throwable)
                0
            }
        }

    // 规则按命中优先级排列：输入法、截图浮层、安全中心浮窗、自由窗和 PiP。
    private fun decideHide(snapshot: WindowSnapshot): HideDecision {
        val hideTaskSurface = snapshot.windowingMode == WINDOWING_MODE_FREEFORM ||
            snapshot.windowingMode == WINDOWING_MODE_PINNED
        val lowerTitle = snapshot.title.lowercase()
        val reason = when {
            snapshot.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD -> HideReason.InputMethod
            snapshot.type == SCREENSHOT_OVERLAY_TYPE || snapshot.packageName == MIUI_SCREENSHOT_PACKAGE -> {
                HideReason.ScreenshotOverlay
            }

            snapshot.packageName == MIUI_SECURITY_CENTER_PACKAGE &&
                (
                    snapshot.type == SECURITY_CENTER_DOCK_TYPE ||
                        snapshot.type == SECURITY_CENTER_FLOATING_TYPE ||
                        lowerTitle.contains("dock") ||
                        lowerTitle.contains("floatingwindow")
                    ) -> HideReason.SecurityCenterOverlay

            hideTaskSurface -> HideReason.WindowingModeOverlay
            else -> null
        }

        return HideDecision(reason = reason, hideTaskSurface = hideTaskSurface)
    }

    // 对窗口 Surface 打标；自由窗和 PiP 还要同步处理 Task Surface，避免父层内容被截到。
    private fun applyHide(
        snapshot: WindowSnapshot,
        decision: HideDecision,
        handles: ReflectionHandles,
    ) {
        when (val result = applySkipScreenshotFlag(snapshot.windowSurface, handles)) {
            ApplyResult.Success -> {
                logStage(
                    priority = Log.INFO,
                    stage = "apply-window",
                    message = "Applied SKIP_SCREENSHOT (0x40) to [${snapshot.title}] (${snapshot.packageName})",
                )
            }

            is ApplyResult.Failure -> {
                logStage(
                    priority = Log.ERROR,
                    stage = "apply-window",
                    message = "${result.operation} failed for [${snapshot.title}] (${snapshot.packageName})",
                    throwable = result.throwable,
                )
            }
        }

        if (!decision.hideTaskSurface) {
            return
        }

        val taskSurface = snapshot.taskSurface
        if (taskSurface != null) {
            when (val result = applySkipScreenshotFlag(taskSurface, handles)) {
                ApplyResult.Success -> {
                    logStage(
                        priority = Log.INFO,
                        stage = "apply-task",
                        message = "Applied SKIP_SCREENSHOT to Task of [${snapshot.title}]",
                    )
                }

                is ApplyResult.Failure -> {
                    logStage(
                        priority = Log.WARN,
                        stage = "apply-task",
                        message = "${result.operation} failed for Task of [${snapshot.title}]",
                        throwable = result.throwable,
                    )
                }
            }
            return
        }

        when (val taskSurfaceState = snapshot.taskSurfaceState) {
            TaskSurfaceReadResult.NotRequested -> {
                logStage(Log.WARN, "apply-task", "Task surface was not requested for [${snapshot.title}]")
            }

            is TaskSurfaceReadResult.Missing -> {
                logStage(Log.WARN, "apply-task", taskSurfaceState.message)
            }

            is TaskSurfaceReadResult.Failure -> {
                logStage(Log.WARN, "apply-task", taskSurfaceState.message, taskSurfaceState.throwable)
            }

            is TaskSurfaceReadResult.Available -> Unit
        }
    }

    // 不同 ROM 版本可能把 Surface 挂在 WindowState 或 Animator 上，这里按稳定性顺序尝试。
    private fun readWindowSurface(windowState: Any, animator: Any): FieldReadResult<Any> {
        val windowStateSurface = readObjectField(windowState, "mSurfaceControl")
        if (windowStateSurface is FieldReadResult.Value) {
            return windowStateSurface
        }

        val animatorSurface = readObjectField(animator, "mSurfaceControl")
        if (animatorSurface is FieldReadResult.Value) {
            return animatorSurface
        }

        return pickPreferredFieldResult(windowStateSurface, animatorSurface)
    }

    // 只有自由窗和 PiP 需要继续向上拿到 Task Surface，普通全屏窗口直接跳过这一步。
    private fun readTaskSurface(windowState: Any, title: String, windowingMode: Int): TaskSurfaceReadResult {
        if (windowingMode != WINDOWING_MODE_FREEFORM && windowingMode != WINDOWING_MODE_PINNED) {
            return TaskSurfaceReadResult.NotRequested
        }

        return when (val taskResult = invokeZeroArg(windowState, "getTask")) {
            is MethodCallResult.Value -> {
                when (val surfaceResult = readObjectField(taskResult.value, "mSurfaceControl")) {
                    is FieldReadResult.Value -> TaskSurfaceReadResult.Available(surfaceResult.value)
                    is FieldReadResult.NullValue -> TaskSurfaceReadResult.Missing("Task surface is null for [$title]")
                    is FieldReadResult.Missing -> {
                        TaskSurfaceReadResult.Missing("${surfaceResult.ownerClass}.${surfaceResult.fieldName} is missing for [$title]")
                    }

                    is FieldReadResult.Failure -> {
                        TaskSurfaceReadResult.Failure("Task surface read failed for [$title]", surfaceResult.throwable)
                    }
                }
            }

            is MethodCallResult.NullValue -> TaskSurfaceReadResult.Missing("Task is null for [$title]")
            is MethodCallResult.Missing -> {
                TaskSurfaceReadResult.Missing("${taskResult.ownerClass}.${taskResult.methodName}() is missing for [$title]")
            }

            is MethodCallResult.Failure -> {
                TaskSurfaceReadResult.Failure("Unable to resolve Task for [$title]", taskResult.throwable)
            }
        }
    }

    // 每次打标使用一笔独立 Transaction，成功和失败都显式 close，避免句柄泄漏。
    private fun applySkipScreenshotFlag(surfaceControl: Any, handles: ReflectionHandles): ApplyResult {
        val transaction = runCatching {
            handles.transactionConstructor.newInstance()
        }.getOrElse { error ->
            return ApplyResult.Failure("newTransaction", unwrapReflectionError(error))
        }

        var result: ApplyResult = ApplyResult.Success
        runCatching {
            handles.setFlagsMethod.invoke(transaction, surfaceControl, SKIP_SCREENSHOT)
        }.onFailure { error ->
            result = ApplyResult.Failure("setFlags", unwrapReflectionError(error))
        }
        if (result === ApplyResult.Success) {
            runCatching {
                handles.applyMethod.invoke(transaction)
            }.onFailure { error ->
                result = ApplyResult.Failure("apply", unwrapReflectionError(error))
            }
        }
        if (result === ApplyResult.Success) {
            runCatching {
                handles.closeMethod.invoke(transaction)
            }.onFailure { error ->
                result = ApplyResult.Failure("close", unwrapReflectionError(error))
            }
            return result
        }

        runCatching {
            handles.closeMethod.invoke(transaction)
        }.onFailure { error ->
            logStage(Log.WARN, "apply-window", "close failed after primary failure", unwrapReflectionError(error))
        }
        return result
    }

    // 反射读字段统一走这里，返回结构化结果方便上层决定继续、跳过或记录日志。
    private fun readObjectField(target: Any, name: String): FieldReadResult<Any> =
        when (val lookup = findField(target.javaClass, name)) {
            is LookupResult.Value -> {
                runCatching {
                    lookup.value.get(target)
                }.fold(
                    onSuccess = { value ->
                        if (value != null) {
                            FieldReadResult.Value(value)
                        } else {
                            FieldReadResult.NullValue(target.javaClass.name, name)
                        }
                    },
                    onFailure = { error ->
                        FieldReadResult.Failure(target.javaClass.name, name, unwrapReflectionError(error))
                    },
                )
            }

            is LookupResult.Missing -> FieldReadResult.Missing(lookup.ownerClass, lookup.memberName)
            is LookupResult.Failure -> FieldReadResult.Failure(lookup.ownerClass, lookup.memberName, lookup.throwable)
        }

    // type 字段属于高频读取字段，单独保留 Int 读取分支避免额外装箱判断。
    private fun readLayoutType(layoutParams: Any): FieldReadResult<Int> =
        when (val lookup = findField(layoutParams.javaClass, "type")) {
            is LookupResult.Value -> {
                runCatching {
                    lookup.value.getInt(layoutParams)
                }.fold(
                    onSuccess = { value -> FieldReadResult.Value(value) },
                    onFailure = { error ->
                        FieldReadResult.Failure(layoutParams.javaClass.name, "type", unwrapReflectionError(error))
                    },
                )
            }

            is LookupResult.Missing -> FieldReadResult.Missing(lookup.ownerClass, lookup.memberName)
            is LookupResult.Failure -> FieldReadResult.Failure(lookup.ownerClass, lookup.memberName, lookup.throwable)
        }

    private fun invokeZeroArg(target: Any, name: String): MethodCallResult<Any> =
        when (val lookup = findZeroArgMethod(target.javaClass, name)) {
            is LookupResult.Value -> {
                runCatching {
                    lookup.value.invoke(target)
                }.fold(
                    onSuccess = { value ->
                        if (value != null) {
                            MethodCallResult.Value(value)
                        } else {
                            MethodCallResult.NullValue(target.javaClass.name, name)
                        }
                    },
                    onFailure = { error ->
                        MethodCallResult.Failure(target.javaClass.name, name, unwrapReflectionError(error))
                    },
                )
            }

            is LookupResult.Missing -> MethodCallResult.Missing(lookup.ownerClass, lookup.memberName)
            is LookupResult.Failure -> MethodCallResult.Failure(lookup.ownerClass, lookup.memberName, lookup.throwable)
        }

    // 字段查找会沿继承链向上走，命中后缓存到具体起始类名，后续直接复用。
    private fun findField(startClass: Class<*>, name: String): LookupResult<Field> {
        val cacheKey = "${startClass.name}#$name"
        fieldCache[cacheKey]?.let { return LookupResult.Value(it) }

        var current: Class<*>? = startClass
        while (current != null) {
            val lookup = runCatching {
                current.getDeclaredField(name).apply {
                    isAccessible = true
                }
            }
            lookup.onSuccess { field ->
                fieldCache[cacheKey] = field
                return LookupResult.Value(field)
            }

            val error = lookup.exceptionOrNull() ?: break
            if (error is NoSuchFieldException) {
                current = current.superclass
                continue
            }

            return LookupResult.Failure(startClass.name, name, unwrapReflectionError(error))
        }

        return LookupResult.Missing(startClass.name, name)
    }

    // 只缓存零参数方法，覆盖当前模块的全部调用场景，也能降低缓存规模。
    private fun findZeroArgMethod(startClass: Class<*>, name: String): LookupResult<Method> {
        val cacheKey = "${startClass.name}#$name()"
        zeroArgMethodCache[cacheKey]?.let { return LookupResult.Value(it) }

        var current: Class<*>? = startClass
        while (current != null) {
            val method = current.declaredMethods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                zeroArgMethodCache[cacheKey] = method
                return LookupResult.Value(method)
            }
            current = current.superclass
        }

        return LookupResult.Missing(startClass.name, name)
    }

    private fun requireZeroArgMethod(startClass: Class<*>, name: String): Method =
        when (val lookup = findZeroArgMethod(startClass, name)) {
            is LookupResult.Value -> lookup.value
            is LookupResult.Missing -> throw NoSuchMethodException("${lookup.memberName}() not found in ${lookup.ownerClass}")
            is LookupResult.Failure -> throw lookup.throwable
        }

    // 优先返回最早暴露出的异常或缺失信息，便于日志反映真实失败点。
    private fun pickPreferredFieldResult(
        primary: FieldReadResult<Any>,
        fallback: FieldReadResult<Any>,
    ): FieldReadResult<Any> = when {
        primary is FieldReadResult.Failure -> primary
        fallback is FieldReadResult.Failure -> fallback
        primary is FieldReadResult.Missing -> primary
        fallback is FieldReadResult.Missing -> fallback
        primary is FieldReadResult.NullValue -> primary
        else -> fallback
    }

    private fun inspectSkip(
        message: String,
        throwable: Throwable? = null,
    ): SnapshotReadResult.Skipped = SnapshotReadResult.Skipped(
        priority = Log.WARN,
        stage = "inspect",
        message = message,
        throwable = throwable,
    )

    private fun unwrapReflectionError(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException) {
            throwable.targetException ?: throwable
        } else {
            throwable
        }

    // 日志统一通过这一层输出，debug 包保留诊断信息，release 包保持静默。
    private fun logStage(
        priority: Int,
        stage: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!debugLoggingEnabled) {
            return
        }

        val formatted = "[$stage] $message"
        if (throwable == null) {
            super.log(priority, TAG, formatted)
        } else {
            super.log(priority, TAG, formatted, throwable)
        }
    }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    // HyperOS 设备通过系统属性判断，属性为空时视为当前 ROM 不在支持范围内。
    private fun readMiOsVersionName(): String =
        runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, "ro.mi.os.version.name", "") as String
        }.getOrDefault("")

    private data class ReflectionHandles(
        val transactionConstructor: Constructor<*>,
        val setFlagsMethod: Method,
        val applyMethod: Method,
        val closeMethod: Method,
    )

    private data class WindowSnapshot(
        val title: String,
        val packageName: String,
        val type: Int,
        val windowingMode: Int,
        val windowSurface: Any,
        val taskSurface: Any?,
        val taskSurfaceState: TaskSurfaceReadResult,
    )

    private data class HideDecision(
        val reason: HideReason?,
        val hideTaskSurface: Boolean,
    )

    private enum class HideReason(val code: String) {
        InputMethod("input-method"),
        ScreenshotOverlay("screenshot-overlay"),
        SecurityCenterOverlay("security-center-overlay"),
        WindowingModeOverlay("windowing-mode-overlay"),
    }

    private sealed interface LookupResult<out T> {
        data class Value<T>(val value: T) : LookupResult<T>
        data class Missing(
            val ownerClass: String,
            val memberName: String,
        ) : LookupResult<Nothing>

        data class Failure(
            val ownerClass: String,
            val memberName: String,
            val throwable: Throwable,
        ) : LookupResult<Nothing>
    }

    private sealed interface FieldReadResult<out T : Any> {
        data class Value<T : Any>(val value: T) : FieldReadResult<T>
        data class NullValue(
            val ownerClass: String,
            val fieldName: String,
        ) : FieldReadResult<Nothing>

        data class Missing(
            val ownerClass: String,
            val fieldName: String,
        ) : FieldReadResult<Nothing>

        data class Failure(
            val ownerClass: String,
            val fieldName: String,
            val throwable: Throwable,
        ) : FieldReadResult<Nothing>
    }

    private sealed interface MethodCallResult<out T : Any> {
        data class Value<T : Any>(val value: T) : MethodCallResult<T>
        data class NullValue(
            val ownerClass: String,
            val methodName: String,
        ) : MethodCallResult<Nothing>

        data class Missing(
            val ownerClass: String,
            val methodName: String,
        ) : MethodCallResult<Nothing>

        data class Failure(
            val ownerClass: String,
            val methodName: String,
            val throwable: Throwable,
        ) : MethodCallResult<Nothing>
    }

    private sealed interface SnapshotReadResult {
        data class Success(val snapshot: WindowSnapshot) : SnapshotReadResult
        data class Skipped(
            val priority: Int,
            val stage: String,
            val message: String,
            val throwable: Throwable? = null,
        ) : SnapshotReadResult
    }

    private sealed interface TaskSurfaceReadResult {
        data object NotRequested : TaskSurfaceReadResult
        data class Available(val surface: Any) : TaskSurfaceReadResult
        data class Missing(val message: String) : TaskSurfaceReadResult
        data class Failure(
            val message: String,
            val throwable: Throwable,
        ) : TaskSurfaceReadResult
    }

    private sealed interface ApplyResult {
        data object Success : ApplyResult
        data class Failure(
            val operation: String,
            val throwable: Throwable,
        ) : ApplyResult
    }

    private companion object {
        const val TAG = "XposedShot"
        const val SKIP_SCREENSHOT = 0x40
        const val WINDOW_STATE_ANIMATOR = "com.android.server.wm.WindowStateAnimator"
        const val CREATE_SURFACE_LOCKED = "createSurfaceLocked"
        const val SURFACE_CONTROL = "android.view.SurfaceControl"
        const val SURFACE_CONTROL_TRANSACTION = $$"android.view.SurfaceControl$Transaction"
        const val SCREENSHOT_OVERLAY_TYPE = 2036
        const val SECURITY_CENTER_DOCK_TYPE = 2026
        const val SECURITY_CENTER_FLOATING_TYPE = 2003
        const val WINDOWING_MODE_PINNED = 2
        const val WINDOWING_MODE_FREEFORM = 5
        const val MIUI_SCREENSHOT_PACKAGE = "com.miui.screenshot"
        const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
    }
}
