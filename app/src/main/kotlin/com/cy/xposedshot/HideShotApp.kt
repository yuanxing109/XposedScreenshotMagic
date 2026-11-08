package com.cy.xposedshot

import android.app.Application
import com.cy.xposedshot.config.ConfigStore

/**
 * 进程启动时绑定框架的 Remote Preferences。
 *
 * 绑定成功后，本 App 与 system_server 里的模块共享同一份配置，改动由框架 daemon 直接推送，
 * 不需要权限、不需要轮询。
 */
class HideShotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.install(this)
    }
}
