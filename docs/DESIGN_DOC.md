# 芦笋分级视觉测量系统设计手册 (DESIGN_DOC)

本手册是芦笋分级系统的核心设计文档库。系统采用 **3D 位姿估算 (Pose Estimation)** 技术，在 3D 空间中精确还原芦笋的物理形态，并实现工业级分级。

## 1. 业务需求与测量标准 (Requirements)
详细定义了芦笋的生物结构、测量逻辑以及分级标准。
- [芦笋生物结构定义](requirements/ASP_BIOLOGY.md)
- [测量逻辑与分级标准](requirements/GRADING_STANDARDS.md)

## 2. 系统设计与架构 (Design)
描述了基于 3D 视觉的位姿解算架构及其实时性优化机制。
- [数据流与 3D 位姿实时处理逻辑](design/DATA_FLOW.md)
- [硬件校准板规格](design/HARDWARE_SPECS.md)
- [软硬件兼容性与诊断](design/COMPATIBILITY_DIAGNOSTICS.md)

## 3. 开发者指南 (Developer)
标准化的开发工作流与调试方法。
- [开发、构建与部署工作流](developer/WORKFLOW.md)
- [视图诊断模式说明](developer/DIAGNOSTICS.md)

---

## 4. 专项研究 (Advanced Analysis)
针对特定问题的深入技术评估。
- [V2 算法：基于 3D 位姿解算的直径测量优化](ANALYSIS_ALGO_V2_3D.md)
- [Level 2 工作流：多帧采样平均与稳定性增强](ANALYSIS_WORKFLOW_LEVEL2.md)
- [环境安装指南](ENVIRONMENT_SETUP.md)
