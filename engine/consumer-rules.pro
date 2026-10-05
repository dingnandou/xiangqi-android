# 引擎层对外只暴露 com.yijin.xiangqi.engine.NativeEngine 的 JNI 方法，
# 其余符号由 Pikafish 的 C++ 实现提供，不需要 JNI 反射调用，因此保留默认行为即可。
# 若后续 M2 起引入反射调用，再在此补充 keep 规则。