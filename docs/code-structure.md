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
│  │  └─ NpcBlockInteraction
│  ├─ action/                     一次具体动作
│  │  ├─ NpcAction、ActionResult
│  │  └─ MoveToAction、BreakBlockAction、PlaceBlockAction、合成和拾取动作
│  └─ task/
│     ├─ NpcTask                  所有长期任务共用的最小接口
│     ├─ NpcTaskRunner            任务的运行、暂停、恢复、取消、读档
│     ├─ NpcTaskTypes             根据稳定的任务 ID 创建对象
│     ├─ NpcTaskExecutionGoal     接入 Minecraft 的 AI 更新
│     ├─ TaskSequence             按顺序执行任意子任务
│     ├─ VisitPositionTask        通用移动任务
│     ├─ FailureMemory            共用的失败冷却记录
│     ├─ build/
│     │  ├─ BuildHutTask          当前小屋场景的阶段编排
│     │  ├─ BuildingSiteSearch    NPC 分批选址、预算和施工入口预检
│     │  ├─ PrepareBuildingSiteTask  清障和泥土整地
│     │  ├─ BuildStructureTask    根据蓝图差异施工
│     │  ├─ ConstructionExecutor 共用的施工动作循环
│     │  └─ ConstructionWorkSelector  施工顺序、支撑、站位和失败冷却
│     └─ resource/
│        ├─ GatherResourceTask    采集并拾取资源，直到库存达标
│        ├─ CraftUntilExhaustedTask  用完匹配的单格合成原料
│        ├─ GatherAndCraftPlanksTask  采集原木再合成木板的场景
│        ├─ EnsurePlanksTask      按木板数量补齐缺口
│        ├─ SupplyMaterialsTask   按最终需求安排采集和合成
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
2. `BuildHutTask` 读取已有库存，推进选址、整地、建屋三个阶段，不负责 NPC 的出生物资。
3. `BuildingSiteSearch` 每次检查少量候选，调用建筑规划计算地形工作与房屋总材料，检查预算和可达施工入口，选中后登记并返回场地。
4. `PrepareBuildingSiteTask` 和 `BuildStructureTask` 分别提供各自的剩余工作计划，共用 `ConstructionExecutor`。
5. `ConstructionWorkSelector` 从计划中挑选当前可执行的操作和站位，执行器安排移动、破坏或放置动作。
6. 实际放置动作按共用规则扣除真实背包材料；暂停或读档后按现场重算剩余工作。

第一次读代码，建议按上述顺序看。查看选址细节就进入 `BuildingSiteSearch`；查看地形是否允许使用，就进入 `BuildingSitePlanner`；查看为什么一个方块不能操作，就进入选择器和 `NpcBlockInteraction`。

## 这次整理解决的职责问题

`NpcTask` 只保留生命周期、活动目标和存档方法。采集的目标放弃、失败标记转发已移除；失败记录仍由实际采集任务维护，冷却查询放在 `GatherResourceTask`，运行器不再暴露采集专用查询。

`TaskSequence` 只理解步骤索引和步骤数据。旧版 `COLLECTING/CRAFTING/COMPLETED` 阶段的含义由 `GatherAndCraftPlanksTask` 负责转换，通用序列不再硬编码资源业务阶段。

`ConstructionTask` 已更名为 `ConstructionOperation`：它是单个坐标的施工数据；整个 NPC 建造任务由 `BuildStructureTask` 表示。

`ConstructionExecutor`、`ConstructionWorkSelector`、`BuildingSiteSearch` 都保持包内可见。其他模块通过公开的建筑任务使用功能，不直接操作这些内部辅助类。

`NpcTaskTypes` 是集中注册入口，因此需要知道各个具体任务。普通任务通过 `NpcTask` 对接运行器，注册时不需要把具体任务知识放回运行器。

## 存档兼容

Java 包名和类名变化不改变任务存档 ID。现有 `wayside:build_hut`、`wayside:build_structure`、`wayside:prepare_building_site` 和资源任务 ID 保持原值，任务仍通过 `NpcTaskTypes` 恢复。

建筑、场地、背包和任务阶段字段保持兼容。整地中的读档、已开始建屋的旧存档，以及 v0.0.6 的阶段存档均有行为测试覆盖。

`NpcInitialSupplies` 只负责新背包配置。实体加载时有保存的背包就完整替换默认值，没有背包字段的初始召唤数据则保留默认值。没有延迟发放、任务发放或按缺口补发逻辑。
