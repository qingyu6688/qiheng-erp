package cn.erp.shared;

import java.util.function.LongConsumer;

/** 固定动作由所属模块登记，控制台不能指定任意类名、脚本或方法。 */
public interface ManagedJobs {void register(String code,LongConsumer action);}
