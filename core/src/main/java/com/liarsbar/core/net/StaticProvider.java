package com.liarsbar.core.net;

/**
 * 静态资源提供者：path 形如 {@code index.html} / {@code js/app.js}，找不到返回 null。
 *
 * <p>{@code com.liarsbar.core.LiarbarServer.StaticProvider} 继承本接口，
 * 桌面端与 Android 端各自实现。</p>
 */
public interface StaticProvider {
    byte[] read(String path);
}
