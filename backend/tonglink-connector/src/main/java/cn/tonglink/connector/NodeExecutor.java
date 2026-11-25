/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.model.flow.GraphNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点执行器 SPI。
 *
 * <p>每种节点能力实现一个执行器，{@link #supports()} 声明支持的 {@code type:subtype}。
 * 新增节点类型时只需新增实现类，注册表会自动收集，无需改动执行引擎。</p>
 *
 * <p>约定：输入输出都是「行列表」，每行是一个 JSON 对象。这样所有节点可以自由串联，
 * 数据量大的场景也不会因为嵌套过深而难以序列化。</p>
 */
public interface NodeExecutor {

    /** 支持的节点类型，格式 {@code type:subtype}。 */
    Set<String> supports();

    /**
     * 执行节点。
     *
     * @param context 执行上下文：执行 ID、流程 ID、节点定义与变量表
     * @param config  节点配置
     * @param input   上游输出
     * @return 输出与分支标识
     */
    ExecResult execute(ExecContext context, Map<String, Object> config, List<Map<String, Object>> input);

    /** 原样透传输入，触发器等节点直接复用。 */
    static ExecResult pass(List<Map<String, Object>> input) {
        return new ExecResult(input, null);
    }

    /**
     * 执行结果。
     *
     * @param output   默认输出行（没有分支的节点使用）
     * @param handle   整体分支标识：条件节点返回 true/false，其余节点为 null
     * @param logs     节点运行期日志（如 console.log / print），由执行引擎写入执行日志
     * @param branches 按端口分流的结果，键为端口名（如 true / false）。条件节点用它把
     *                 每一行数据送入各自的分支，其余节点为 null
     */
    record ExecResult(List<Map<String, Object>> output, String handle, List<String> logs,
                      Map<String, List<Map<String, Object>>> branches) {

        /** 兼容不产生运行期日志的执行器。 */
        public ExecResult(List<Map<String, Object>> output, String handle) {
            this(output, handle, List.of(), null);
        }

        /** 兼容产生日志但不分流的执行器。 */
        public ExecResult(List<Map<String, Object>> output, String handle, List<String> logs) {
            this(output, handle, logs, null);
        }

        /** 构造一个只按端口分流的结果。 */
        public static ExecResult branched(Map<String, List<Map<String, Object>>> branches) {
            List<Map<String, Object>> all = new java.util.ArrayList<>();
            branches.values().forEach(all::addAll);
            return new ExecResult(all, null, List.of(), branches);
        }
    }

    /**
     * 执行上下文。
     *
     * @param executionId 执行 ID，日志关联用
     * @param flowId      流程 ID
     * @param node        当前节点
     * @param vars        变量表（已解密），供表达式引用
     */
    record ExecContext(String executionId, String flowId, GraphNode node, Map<String, Object> vars) {
    }
}
