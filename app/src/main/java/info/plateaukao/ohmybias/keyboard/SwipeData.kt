package info.plateaukao.ohmybias.keyboard

/// 按鍵上滑/下滑動作表 — 取自 sweetlime 皮膚 swipeData.libsonnet。
/// 上滑＝符號、下滑＝數字；第三排下滑＝編輯功能（行首/貼上/Tab/行尾）。
/// 皮膚中的剪下/複製/全選需 host app 配合，IME 無 API，故不移植。
object SwipeData {

    data class Entry(val hint: String?, val action: KeyAction)

    /// 字母頁上滑（中英共用；動作皆半形直接輸出）
    val up: Map<String, Entry> = mapOf(
        "1" to Entry("!",  KeyAction.Symbol("!")),
        "2" to Entry("@",  KeyAction.Symbol("@")),
        "3" to Entry("#",  KeyAction.Symbol("#")),
        "4" to Entry("$",  KeyAction.Symbol("$")),
        "5" to Entry("%",  KeyAction.Symbol("%")),
        "6" to Entry("^",  KeyAction.Symbol("^")),
        "7" to Entry("&",  KeyAction.Symbol("&")),
        "8" to Entry("*",  KeyAction.Symbol("*")),
        "9" to Entry("(",  KeyAction.Symbol("(")),
        "0" to Entry(")",  KeyAction.Symbol(")")),
        "-" to Entry("_",  KeyAction.Symbol("_")),
        "=" to Entry("+",  KeyAction.Symbol("+")),
        "[" to Entry("{",  KeyAction.Symbol("{")),
        "]" to Entry("}",  KeyAction.Symbol("}")),
        "\\" to Entry("|",  KeyAction.Symbol("|")),
        ";" to Entry(":",  KeyAction.Symbol(":")),
        "'" to Entry("\"",  KeyAction.Symbol("\"")),
        "," to Entry("<",  KeyAction.Symbol("<")),
        "." to Entry(">",  KeyAction.Symbol(">")),
        "/" to Entry("?",  KeyAction.Symbol("?")),
        "`" to Entry("~",  KeyAction.Symbol("~")),
    )

    /// 字母頁下滑
    val down: Map<String, Entry> = mapOf(
        "e" to Entry("End", KeyAction.LineEnd),
        "y" to Entry("Redo", KeyAction.Redo),
        "a" to Entry("SelAll", KeyAction.SelectAll),
        "h" to Entry("Home", KeyAction.LineStart),
        "z" to Entry("Undo", KeyAction.Undo),
        "c" to Entry("Copy", KeyAction.Copy),
        "v" to Entry("Paste", KeyAction.PasteClipboard),
        "b" to Entry("Tab", KeyAction.Tab),
        "n" to Entry("2nd", KeyAction.SelectCandidateShortcut(1)),
        "m" to Entry("3rd", KeyAction.SelectCandidateShortcut(2)),
    )
}
