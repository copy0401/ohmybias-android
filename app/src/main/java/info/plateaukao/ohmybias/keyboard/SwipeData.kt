package info.plateaukao.ohmybias.keyboard

/// 按鍵上滑/下滑動作表 — 取自 sweetlime 皮膚 swipeData.libsonnet。
/// 上滑＝符號、下滑＝數字；第三排下滑＝編輯功能（行首/貼上/Tab/行尾）。
/// 皮膚中的剪下/複製/全選需 host app 配合，IME 無 API，故不移植。
object SwipeData {

    data class Entry(val hint: String?, val action: KeyAction)

    /// 字母頁上滑（中英共用；動作皆半形直接輸出）
    val up: Map<String, Entry> = mapOf(
        "q" to Entry("!", KeyAction.Symbol("!")),
        "w" to Entry("@", KeyAction.Symbol("@")),
        "e" to Entry("#", KeyAction.Symbol("#")),
        "r" to Entry("$", KeyAction.Symbol("$")),
        "t" to Entry("%", KeyAction.Symbol("%")),
        "y" to Entry("^", KeyAction.Symbol("^")),
        "u" to Entry("&", KeyAction.Symbol("&")),
        "i" to Entry("*", KeyAction.Symbol("*")),
        "o" to Entry("(", KeyAction.Symbol("(")),
        "p" to Entry(")", KeyAction.Symbol(")")),
        "a" to Entry("`", KeyAction.Symbol("`")),
        "s" to Entry("-", KeyAction.Symbol("-")),
        "d" to Entry("=", KeyAction.Symbol("=")),
        "f" to Entry("【", KeyAction.Symbol("【")),
        "g" to Entry("】", KeyAction.Symbol("】")),
        "h" to Entry("\\", KeyAction.Symbol("\\")),
        "j" to Entry("/", KeyAction.Symbol("/")),
        "k" to Entry("；", KeyAction.Symbol(";")),
        "l" to Entry("'", KeyAction.Symbol("'")),
        "x" to Entry("[", KeyAction.Symbol("[")),
        "c" to Entry("]", KeyAction.Symbol("]")),
        "v" to Entry("<", KeyAction.Symbol("<")),
        "b" to Entry(">", KeyAction.Symbol(">")),
        "n" to Entry("2nd", KeyAction.SelectCandidateShortcut(1)),
        "m" to Entry("3rd", KeyAction.SelectCandidateShortcut(2)),
        "," to Entry(null, KeyAction.Symbol(",")),
        "." to Entry(null, KeyAction.Symbol(".")),
    )

    /// 字母頁下滑
    val down: Map<String, Entry> = mapOf(
        "q" to Entry("1", KeyAction.Symbol("1")),
        "w" to Entry("2", KeyAction.Symbol("2")),
        "e" to Entry("3", KeyAction.Symbol("3")),
        "r" to Entry("4", KeyAction.Symbol("4")),
        "t" to Entry("5", KeyAction.Symbol("5")),
        "y" to Entry("6", KeyAction.Symbol("6")),
        "u" to Entry("7", KeyAction.Symbol("7")),
        "i" to Entry("8", KeyAction.Symbol("8")),
        "o" to Entry("9", KeyAction.Symbol("9")),
        "p" to Entry("0", KeyAction.Symbol("0")),
        "a" to Entry("~", KeyAction.Symbol("~")),
        "s" to Entry("_", KeyAction.Symbol("_")),
        "d" to Entry("+", KeyAction.Symbol("+")),
        "f" to Entry("{", KeyAction.Symbol("{")),
        "g" to Entry("}", KeyAction.Symbol("}")),
        "h" to Entry("|", KeyAction.Symbol("|")),
        "j" to Entry("?", KeyAction.Symbol("?")),
        "k" to Entry(":", KeyAction.Symbol(":")),
        "l" to Entry("\"", KeyAction.Symbol("\"")),
        "z" to Entry("句首", KeyAction.LineStart),
        "v" to Entry("貼上", KeyAction.PasteClipboard),
        "b" to Entry("Tab", KeyAction.Tab),
        "m" to Entry("句尾", KeyAction.LineEnd),
        "," to Entry(null, KeyAction.Symbol("、")),
        "." to Entry(null, KeyAction.Symbol("！")),
    )
}
