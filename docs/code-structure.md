# 代码结构与阅读顺序

当前任务按“公共基础、建筑、资源”分组。每组只增加一层子包，建筑规划和具体执行仍分别放置。

```text
wayside/
├─ entity/
│  ├─ NpcEntity
│  ├─ inventory/                  背包操作
│  │  ├─ InventoryOps
│  │  └─ NpcInitialSupplies        出生时的默认物资配置
│  ├─ ai/                         寻找操作站位、距离和视线检查
│  │  ├─ NpcBlockInteraction
│  │  └─ NpcMovementSafety          安全落差与末端移动的地面支撑
│  ├─ action/                     一次具体动作
│  │  ├─ NpcAction、ActionResult
│  │  └─ MoveToAction、BreakBlockAction、PlaceBlockAction、PillarUpAction、合成和拾取动作
│  └─ task/
│     ├─ NpcTask                  所有长期任务共用的最小接口
│     ├─ NpcTaskRunner            任务的运行、暂停、恢复、取消、读档
│     ├─ NpcTaskTypes             根据稳定的任务 ID 创建对象
│     ├─ NpcTaskExecutionGoal     接入 Minecraft 的 AI 更新
│     ├─ CompositeTask            向当前子任务转发暂停和目标查询
│     ├─ TaskSequence             嵌套顺序执行；具名步骤可以延迟创建
│     ├─ VisitPositionTask        通用移动任务
│     ├─ FailureMemory            共用的失败冷却记录
│     ├─ build/
│     │  ├─ BuildHutTask          小屋蓝图和选址参数
│     │  ├─ BuildBuildingTask     通用建造任务链
│     │  ├─ BuildingProject       一次建造的输入与选址结果
│     │  ├─ SelectBuildingSiteTask  NPC 分批选址、方块支持和施工入口预检
│     │  ├─ PrepareBuildingMaterialsTask  合计地基与房屋需求，调用补给任务
│     │  ├─ PrepareBuildingSiteTask  清障和泥土整地
│     │  ├─ BuildStructureTask    根据蓝图差异施工
│     │  ├─ ConstructionExecutor 共用的施工动作循环
│     │  ├─ ConstructionWorkSelector  施工顺序、支撑、站位和失败冷却
│     │  ├─ ConstructionAccessPlanner  普通站位不可达时的有限垫高判断
│     │  └─ ConstructionRecovery       垫高预算、限制、冷却与进度保存
│     └─ resource/
│        ├─ GatherResourceTask    采集并拾取资源，直到库存达标
│        ├─ CraftUntilExhaustedTask  用完匹配的单格合成原料
│        ├─ GatherAndCraftPlanksTask  采集原木再合成木板的场景
│        ├─ EnsurePlanksTask      按木板数量补齐缺口
│        ├─ SupplyMaterialsTask   判断最终需求，创建采集与合成子链
│        ├─ CraftRecipeTask       具体配方任务，直接调用移动与合成动作
│        └─ ItemRequirement       基于物品标签的数量需求
├─ building/                     建筑数据、地形规划和材料计算
│  ├─ BuildingBlueprint          读取原版结构蓝图
│  ├─ BuildingInstance           建筑身份、原点与朝向
│  ├─ BuildingManager、BuildingSavedData  登记、占地预留和存档
│  ├─ BuildingSitePlanner、BuildingSitePlan  只读的场地规划与剩余土方
│  ├─ ConstructionPlanner        比较目标状态和现场，产生操作清单
│  ├─ ConstructionOperation      一个坐标上的放置、拆除或替换数据
│  ├─ BuildingMaterialCalculator 将操作清单换算为物品数量
│  └─ BlockPlacementRules        单个方块状态的支持范围及实际材料成本
└─ material/                     根据配方计算材料供给计划
   └─ MaterialPlanner、MaterialPlan
```

## 自动建屋怎么串起来

1. `NpcEntity` 在服务端构造时通过 `NpcInitialSupplies` 创建带物资的背包；默认任务工厂传入运行器，运行器通过 `NpcTaskExecutionGoal` 得到更新机会。
2. `BuildHutTask` 只提供小屋参数，复用 `BuildBuildingTask` 的“选址 → 准备材料 → 整地 → 建屋”任务链，蓝图读取并入初始化。缺材料时由备料步骤调用更深层的采集和合成任务链。
3. `SelectBuildingSiteTask` 每次检查少量候选，检查地形、材料支持范围和可达施工入口，选中后登记并返回场地。之后 `PrepareBuildingMaterialsTask` 合计地基与房屋需求，交给 `SupplyMaterialsTask`。
4. `PrepareBuildingSiteTask` 和 `BuildStructureTask` 分别提供各自的剩余工作计划，共用 `ConstructionExecutor`。
5. `ConstructionWorkSelector` 从计划中挑选当前可执行的操作和站位，执行器安排移动、破坏或放置动作。
6. 实际放置动作按共用规则扣除真实背包材料；暂停或读档后按现场重算剩余工作。

第一次读代码，建议按上述顺序看。查看选址细节就进入 `SelectBuildingSiteTask`；查看地形是否允许使用，就进入 `BuildingSitePlanner`；查看为什么一个方块不能操作，就进入选择器和 `NpcBlockInteraction`。

## 这次整理解决的职责问题

`NpcTask` 只保留生命周期、活动目标和存档方法。采集的目标放弃、失败标记转发已移除；失败记录仍由实际采集任务维护，冷却查询放在 `GatherResourceTask`，运行器不再暴露采集专用查询。

`TaskSequence` 只理解步骤名称、索引、工厂和子任务数据，不理解建筑或资源业务。固定任务与具名延迟步骤都能继续嵌套；`CompositeTask` 统一转发当前分支的暂停和目标查询。旧资源阶段转换已移除。

`ConstructionTask` 已更名为 `ConstructionOperation`：它是单个坐标的施工数据；整个 NPC 建造任务由 `BuildStructureTask` 表示。

`ConstructionExecutor`、`ConstructionWorkSelector`、`SelectBuildingSiteTask` 都保持包内可见。其他模块通过公开的建筑任务使用功能，不直接操作这些内部辅助类。

`NpcTaskTypes` 是集中注册入口，因此需要知道各个具体任务。普通任务通过 `NpcTask` 对接运行器，注册时不需要把具体任务知识放回运行器。

## 任务链与存档

任务树、延迟创建、材料获取范围和保存策略见 [层级任务说明](task-hierarchy.md)。本次使用新的步骤和建造进度格式，不迁移旧任务进度。外层任务仍通过 `NpcTaskTypes` 按 ID 恢复；子任务由父任务工厂重建，当前索引和必要的深层数据由任务链递归保存。

建造保存选定场地，不会因暂停或读档重新选址；备料保存最终需求，按真实库存重算未完成操作。

`NpcInitialSupplies` 只负责新背包配置。实体加载时有保存的背包就完整替换默认值，没有背包字段的初始召唤数据则保留默认值。没有延迟发放、任务发放或按缺口补发逻辑。

施工顺序及正常放置受阻后的处理见 [施工顺序与放置兜底](construction-placement.md)。施工辅助移动见 [垫脚与山边移动说明](npc-access.md)。`PillarUpAction` 只执行一次垫高，触发由施工执行器和只读辅助规划器负责，物品扣除及放置事务继续共用已有类。

背包的通用计数、单格原料换产物、快照提交集中在 `InventoryOps`。单格合成使用 `exchange`，配方合成使用 `replaceContents` 提交已检查的库存。

`NpcEntity` 不再提供 `getLogCount`、`getPlankCount` 等物品专用接口。调用方通过 `InventoryOps.count(inventory, item/tag/predicate)` 查询数量；`ItemRequirement` 只表达标签库存目标和缺口。`GatherResourceTask` 只接收方块筛选、物品筛选和目标数量，拾取筛选与库存统计使用同一物品条件，不再接收独立的实体计数回调。

`NpcTaskRunner.temporarilyRun(NpcTask)` 接收任意临时任务，结束后恢复原任务；`cancelTemporaryTask()` 取消临时任务。运行器不创建移动任务，也不暴露手动移动专用接口。需要临时移动的调用方自行传入 `VisitPositionTask`。

`GatherAndCraftPlanksTask` 是“采集到指定原木量后全部合成”的场景，`EnsurePlanksTask` 是“木板够用就停止”的场景；二者语义不同，且已有存档 ID，继续保留。通用采集与合成能力仍由它们组合使用。
