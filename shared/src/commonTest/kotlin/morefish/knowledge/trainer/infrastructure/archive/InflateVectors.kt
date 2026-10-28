// 由 tools/qbank/tests/gen_inflate_vectors.py 生成，请勿手改。
// 生成时间：2026-10-19  来源：CPython zlib（raw deflate, wbits=-15）
package morefish.knowledge.trainer.infrastructure.archive

/**
 * raw DEFLATE 测试向量：`deflate` 是 zlib 压出来的字节流，
 * `plainSha256` 是解压后应得的原文哈希，`plainSize` 是原文长度。
 *
 * 只存哈希不存原文，避免测试源码被大段文本淹没；原文由解压结果反推验证。
 */
internal object InflateVectors {
    data class Vector(
        val name: String,
        val deflateBase64: String,
        val plainSize: Int,
        val plainSha256: String,
        val blockType: Int,
    )

    val all: List<Vector> = listOf(
        Vector(
            name = "empty",
            deflateBase64 = "AwA=",
            plainSize = 0,
            plainSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            blockType = 1,
        ),
        Vector(
            name = "stored_short",
            deflateBase64 = "AREA7v9oZWxsbyBoZWxsbyBoZWxsbw==",
            plainSize = 17,
            plainSha256 = "d4e10166a657fc3f93818e86c62b43bf5e8919edd4130d219009a0108b130cd9",
            blockType = 0,
        ),
        Vector(
            name = "fixed_huffman",
            deflateBase64 = "S0xKThxFsBAAAA==",
            plainSize = 270,
            plainSha256 = "7eeccc16fc757e3a4845778e093cc53fa285651b4e655baf4ec66ace2adc9f57",
            blockType = 1,
        ),
        Vector(
            name = "chinese_utf8",
            deflateBase64 =
                "e7loxtNdk58t2PN0T/+L5S0veprf75n1ctGM57PXvdi1+snuvU929D2bse/5kl3PprU/W9jxtKPt+bLdjxuaXo5q" +
                "HNVIX40A",
            plainSize = 684,
            plainSha256 = "9ef8fe6a98ec49d1c537abff58563359b1bbbb811faf35d038fc6082d19bfab8",
            blockType = 1,
        ),
        Vector(
            name = "random_incompressible",
            deflateBase64 =
                "AQAB//6lTcoYJTC7HW0TLN7WI3su2R4/ch/LGXEXRJTWSTydXDRgvjEgHmn+2qDu6LmZf1x8KZn9r+WTJTzWVK9N" +
                "+tcUJ6Cus/7pIy+K8iEfnuSRxbEL7LVWO/web5NCfsvI/ilV5c2ORtyO1LfCdk0qWk12dwb4XYaQAkrWvaNAG+nI" +
                "y8zJNfbNH2EiauFTOK4aNABNM7oNJGrATIGxuvI+O/nu9fefK0k0r4f1UgtpuUsNmC6Fu1W2cqhyY3rNdGb8tg4O" +
                "j/GEY7DksropcDR08GSsaPcA9bArPcZm9FveqizK7c0rUVdBDk3uSvKzT0MKBzRH3mNsDoBslXumhNZDH7Xq",
            plainSize = 256,
            plainSha256 = "dbb3d810b12898509d37d2301beca56fbefef5643d4b86f3c477d481acdea1d2",
            blockType = 0,
        ),
        Vector(
            name = "long_distance_repeat",
            deflateBase64 =
                "7cmxCcAgEEDRPlPcAhGzgoXNVYILCDlEEAUb13cP+a99Oubu9leTvEobtkTzm0KUz/lHSZIkSZIkSZIkSZIkydvz" +
                "AA==",
            plainSize = 4640,
            plainSha256 = "6d5e3898ad9e2fa1657c02e34a72e6dabbbf75e27f6527a727f546f3e3ff15bc",
            blockType = 2,
        ),
        Vector(
            name = "multi_block",
            deflateBase64 =
                "nd27jV5VFIDRnCpcwr9f91ECfUBAgoWAHCHRAzIiMJElRk7IbBDFgBkc0QI17FXAjb7tx6w5Z5/np6fHPz+//u/3" +
                "Vx9/+fH5p7f/vvv17/d/vPjq28+//uaLl19++tmLD3/+8PH1++c3v314++qv777/5PnpKdZf5PqLWn/R6y9m/cWx" +
                "/uJcf3Gtv7j3BSH6vnrss8e+e+zDx7587NPHvn3s48e+fu7rJ/yZ39fPff3c1899/dzXz3393NfPff3a1699/YK/" +
                "8vf1a1+/9vVrX7/29Wtfv/b1e1+/9/V7X7/hX/x9/d7X73393tfvff3e1599/dnXn3392dcf+A/fvv7s68++/uzr" +
                "z77+sa9/7Osf+/rHvv6xr3/A//f39Y99/WNf/9jXP/f1z339c1//3Nc/9/XPff0Tftzb1z/39c99/Wtf/9rXv/b1" +
                "r339a1//2te/9vUv+Gl/X//a17/39e99/Xtf/97Xv/f17339e1//3te/AXtEe4B7HuA9DwCfB4jPA8jnAebzAPR5" +
                "gPo8gH0eMAfEfjAHAn8if0J/Yn+Cf6J/wn/gfwEAGCn+C3MABhiAgAEKGMCAAQ4YAIEBEhhAgQEWGCW/CIA5AA4M" +
                "8MAAEAwQwQASDDDBABQMUMEAFoyW3wjBHIAMBtBggA0G4GCADgbwYIAPBgBhgBDGyK8GYQ4ACQOUMIAJA5wwAAoD" +
                "pDCACgOsMAAL45DfEcMcgBcGgGGAGAaQYYAZBqBhgBoGsGGAG8YphwVgDoAOA+wwAA8D9DCADwP8MAAQAwQxgBDj" +
                "klMjMAegiAGMGOCIAZAYIIkBlBhgiQGYGKCJccvxITk/BAeIwBMTPDHBExM8McETEzwxwRMTPDHBEzPkIBnMAXhi" +
                "gicmeGKCJyZ4YoInJnhiynlCOVBIJwphDuRMoRwqlFOFcqxQzhXKwULwxARPTPDELDlaCnMAnpjgiQmemOCJCZ6Y" +
                "4IkJnpjgiQmemC1njGEOwBMTPDHBExM8McETEzwxwRMTPDHBE3PksDnMAXhigicmeGKCJyZ4YoInJnhigicmeGIe" +
                "cusA5gA8McETEzwxwRMTPDHBExM8McETEzwxT7l+AnMAnpjgiQmemOCJCZ6Y4IkJnpjgiQmemJfcQ4I5AE9M8MQE" +
                "T0zwxARPTPDEBE9M8MQET8xbLqTJjTS4kgaeWOCJBZ5Y4IkFnljgiQWeWOCJBZ5YIVcTYQ7AEws8scATCzyxwBML" +
                "PLHAEws8scATK+WOKswBeGKBJxZ4YoEnFnhigSeW3FSWq8pyV5kuK8McyHVlua8sF5blxrJcWQZPLPDEAk8s8MRq" +
                "ubUOcwCeWOCJBZ5Y4IkFnljgiQWeWOCJBZ5YI+sLYA7AEws8scATCzyxwBMLPLHAEws8scAT65A9FjAH4IkFnljg" +
                "iQWeWOCJBZ5Y4IkFnljgiXXKQhOYA/DEAk8s8MQCTyzwxAJPLPDEAk8s8MS6ZLMNzAF4YoEnFnhigScWeGKBJxZ4" +
                "YoEnFnhi3bLiSHYcwZIj8MQGT2zwxAZPbPDEBk9s8MQGT2zwxA5ZdgVzAJ7Y4IkNntjgiQ2e2OCJDZ7Y4IkNntgp" +
                "W89gDsATGzyxwRMbPLHBExs8scETGzyxwRO7ZP0dzAF4YoMnNnhigyc2eGLLDkRZgihbEGUNIu1BhDmQTYiyClF2" +
                "IcoyRPDEBk9s8MQGT2zwxB5ZiAlzAJ7Y4IkNntjgiQ2e2OCJDZ7Y4IkNntiHbEaFOQBPbPDEBk9s8MQGT2zwxAZP" +
                "bPDEBk/sU1bkwhyAJzZ4YoMnNnhigyc2eGKDJzZ4YoMn9iW7kmEOwBMbPLHBExs8scETGzyxwRMbPLHBE/uWpdmy" +
                "NRvWZoMnDnjigCcOeOKAJw544oAnDnjigCdOyPp0mAPwxAFPHPDEAU8c8MQBTxzwxAFPHPDESdmjD3MAnjjgiQOe" +
                "OOCJA5444IkDnjjgiQOeOCUPKsAcgCcOeOKAJw544oAnDnjigCcOeOKAJ07LyxowB+CJA5444IkDnjjyuoo8ryLv" +
                "q8gDK/LCCj2xAnMgj6zIKyvyzAp44oAnDnjigCcOeOKAJ84hb+3AHIAnDnjigCcOeOKAJw544oAnDnjigCfOKY8u" +
                "wRyAJw544oAnDnjigCcOeOKAJw544oAnziWvb8EcgCcOeOKAJw544oAnDnjigCcOeOKAJ84tz7At5+B/",
            plainSize = 31090,
            plainSha256 = "1b77b88c8a3e92d720a9f985d254e9a4c33e28ea5054fdd778d269cce51a60ef",
            blockType = 2,
        ),
    )
}
