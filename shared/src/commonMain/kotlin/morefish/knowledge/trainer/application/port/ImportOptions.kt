package morefish.knowledge.trainer.application.port

/**
 * 题型含糊时用户的选择。`AUTO` 表示"不猜，遇到歧义就报告并让用户决定"。
 *
 * 放在 application/port 而不是导入器旁边：它是**用例的输入选项** —— 界面要能传它、
 * 导入实现要能读它，两边都不该为了一个枚举去依赖对方的包。
 * （架构测试 `ArchitectureTest` 会盯住这一点：presentation 不得直接依赖 infrastructure。）
 */
enum class ChoiceMapping { AUTO, SINGLE, MULTIPLE }
