package morefish.knowledge.trainer.presentation

/**
 * 内置示例。刻意只包含结构完整、无歧义的题目，
 * 用来验证"导入 → 作答 → 判题 → 历史"整条链路。
 * 真实题库应通过 KT-QBF 制包工具生成，而不是靠手写。
 */
object SampleBank {

    val json: String = """
        [
          {
            "questionId": "demo-q1",
            "type": "single_choice",
            "question": "TCP 三次握手的主要作用是什么？",
            "options": [
              {"optionId": "q1-a", "contentMarkdown": "断开连接"},
              {"optionId": "q1-b", "contentMarkdown": "建立可靠连接并同步初始序列号"},
              {"optionId": "q1-c", "contentMarkdown": "对传输数据加密"}
            ],
            "correctOptionIds": ["q1-b"],
            "explanation": "三次握手确认双方收发能力正常，并同步初始序列号。"
          },
          {
            "questionId": "demo-q2",
            "type": "single_choice",
            "question": "在 Kotlin 中，下列哪个关键字用于声明一个不可变的局部变量？",
            "options": ["var", "val", "const", "lateinit"],
            "correctOptionIds": ["2"],
            "explanation": "val 声明只读引用；var 声明可变引用。",
            "shuffleAllowed": true
          },
          {
            "questionId": "demo-q3",
            "type": "multiple_choice",
            "question": "以下哪些属于实践的基本特征？",
            "options": [
              {"optionId": "q3-a", "contentMarkdown": "客观物质性"},
              {"optionId": "q3-b", "contentMarkdown": "自觉能动性"},
              {"optionId": "q3-c", "contentMarkdown": "社会历史性"},
              {"optionId": "q3-d", "contentMarkdown": "纯粹主观性"}
            ],
            "correctOptionIds": ["q3-a", "q3-b", "q3-c"],
            "explanation": "前四项中只有前三项属于实践的基本特征。"
          },
          {
            "questionId": "demo-q4",
            "type": "multiple_choice",
            "question": "下列哪些属于 Kotlin 协程的挂起函数调用规则？",
            "options": [
              {"optionId": "q4-a", "contentMarkdown": "挂起函数只能在协程或其他挂起函数中调用"},
              {"optionId": "q4-b", "contentMarkdown": "挂起函数会阻塞当前线程"},
              {"optionId": "q4-c", "contentMarkdown": "挂起函数可以被取消"}
            ],
            "correctOptionIds": ["q4-a", "q4-c"],
            "explanation": "挂起函数挂起协程而不是线程，因此不会阻塞线程。"
          },
          {
            "questionId": "demo-q5",
            "type": "true_false",
            "question": "SQLite 的写入操作在默认配置下支持多写者并发写入同一个数据库文件。",
            "options": ["正确", "错误"],
            "answer": "错误",
            "explanation": "默认 rollback journal 模式下同一时刻只允许一个写者。"
          },
          {
            "questionId": "demo-q6",
            "type": "short_answer",
            "question": "默写：什么是实践？",
            "answer": "实践是人类能动地改造世界的客观物质活动。",
            "explanation": "严格默写模式逐字符比对标点与空格，差异会在反馈中标注。"
          }
        ]
    """.trimIndent()
}
