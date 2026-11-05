package kcl.realwayfinder.nav

enum class ScriptRole(val file: String, val title: String, val detail: String) {
	SCAN(
		"scan",
		"扫点",
		"整点收到 SpotFilter: island spots refreshed 时播放一次。与寻路开关无关"
	),
	RETURN(
		"return",
		"回出生点",
		"开始扫该岛之前播放一次，回到这个岛的出生点。普通寻路不会自动回家"
	),
	ENTER(
		"enter",
		"进入",
		"检测到 grotto 时播放，进入后做一次扫点分析"
	)
}
