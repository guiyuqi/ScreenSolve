# ScreenSolve R8 规则
#
# 原则：业务代码正常混淆/裁剪，第三方"靠反射或内部名字工作"的库整包保留。
# 体积代价很小（jlatexmath 0.6 MB + Markwon 0.1 MB，release 上多几十 KB），
# 换来的是"混淆后公式还能画出来"这件必须成立的事。

# --- 公式渲染 ---
# jlatexmath 通过反射查符号表、字体资源（assets 里的 jlm_*.ttf），类名/成员名被改就画不出来。
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }

# Markwon 的插件体系按接口类型查找实现，且部分节点渲染走反射。
-keep class io.noties.markwon.** { *; }

# --- 我们自己通过清单/反射引用的组件 ---
# 清单里声明过的 Activity/Service，R8 一般会自动保留；这里显式写出，避免以后重构时被裁。
-keep class com.fragranstea.screensolve.ui.overlay.OverlayService { *; }
-keep class com.fragranstea.screensolve.ui.main.MainActivity { *; }
-keep class com.fragranstea.screensolve.ui.settings.SettingsActivity { *; }
-keep class com.fragranstea.screensolve.ui.history.HistoryActivity { *; }
-keep class com.fragranstea.screensolve.ui.history.ConversationActivity { *; }
-keep class com.fragranstea.screensolve.ui.region.RegionSelectActivity { *; }
-keep class com.fragranstea.screensolve.ui.consent.ConsentActivity { *; }

# ML Kit（只有 full 版引入）：入口类由清单与初始化回调引用
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
