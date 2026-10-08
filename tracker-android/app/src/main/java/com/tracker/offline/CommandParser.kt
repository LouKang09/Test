package com.tracker.offline

import java.time.LocalDate

data class ParsedExpense(val amount: Int, val category: String, val item: String)

object CommandParser {
    private val digits = Regex("""(?i)(?:₱\s*|php\s*|p\s*)([0-9][0-9,]*)|([0-9][0-9,]*)\s*(?:pesos?|php|₱)""")
    private val anyDigits = Regex("""\b([0-9][0-9,]*)\b""")
    private val wordNums = mapOf("one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,
        "six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,"eleven" to 11,
        "twelve" to 12,"thirteen" to 13,"fourteen" to 14,"fifteen" to 15,"sixteen" to 16,
        "seventeen" to 17,"eighteen" to 18,"nineteen" to 19,"twenty" to 20,"thirty" to 30,
        "forty" to 40,"fifty" to 50,"sixty" to 60,"seventy" to 70,"eighty" to 80,
        "ninety" to 90,"hundred" to 100,"thousand" to 1000)
    private val categoryWords = linkedMapOf(
        "Transportation" to listOf("transport","transportation","jeep","jeepney","bus","taxi","grab","fare","pamasahe","tricycle","commute","gasoline"),
        "Food" to listOf("food","snack","snacks","meal","lunch","dinner","breakfast","ate","kain","ulam","coke","coffee","drink","merienda","restaurant","burger","rice"),
        "Bills" to listOf("bill","rent","electric","water","internet","load","electricity"),
        "Shopping" to listOf("shopping","clothes","grocery","groceries","shoes"),
        "Health" to listOf("medicine","hospital","doctor","health","pharmacy"))
    private fun spokenNumber(t: String): Int? {
        val phrase = Regex("""(?i)\b((?:(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)[ -]?)+)\s+pesos?\b""").find(t)?.groupValues?.get(1) ?: return null
        var current = 0; var total = 0
        for(w in phrase.split(Regex("[ -]+"))) {
            val n=wordNums[w] ?: continue
            when(n) {
                100 -> current = (if(current==0) 1 else current)*100
                1000 -> { total += (if(current==0) 1 else current)*1000; current=0 }
                else -> current += n
            }
        }
        return (total+current).takeIf { it>0 }
    }
    fun parse(input: String): ParsedExpense? {
        val t=input.trim().lowercase()
        if(t.isBlank() || Regex("""\b(?:undo|remove|delete|cancel)\b""").containsMatchIn(t)) return null
        val d=digits.find(t)
        val raw=d?.groupValues?.drop(1)?.firstOrNull{it.isNotBlank()} ?: anyDigits.find(t)?.groupValues?.get(1)
        val amount = raw?.replace(",","")?.toIntOrNull() ?: spokenNumber(t) ?: return null
        if(amount<=0 || amount>1_000_000) return null
        val explicit = Regex("""\b(?:as|under|category|sa|for|to)\s+(transportation|transport|food|bills|shopping|health|other)\b""").find(t)?.groupValues?.get(1)
        val category = when(explicit) {
            "transport","transportation"->"Transportation"
            "food"->"Food";"bills"->"Bills";"shopping"->"Shopping";"health"->"Health";"other"->"Other"
            else -> categoryWords.entries.firstOrNull { (_,words)->words.any {
                Regex("""\b"""+Regex.escape(it)+"""\b""").containsMatchIn(t)
            } }?.key ?: "Other"
        }
        val item = when {
            "coke" in t -> "Coke"
            "coffee" in t -> "Coffee"
            Regex("""\bsnacks?\b""").containsMatchIn(t) -> "Snacks"
            "jeep" in t -> "Jeepney"
            "bus" in t -> "Bus"
            "lunch" in t -> "Lunch"
            "dinner" in t -> "Dinner"
            "breakfast" in t -> "Breakfast"
            "burger" in t -> "Burger"
            "rice" in t -> "Rice"
            else -> category
        }
        return ParsedExpense(amount,category,item)
    }
}
object BudgetLogic {
    fun spentToday(items:List<Expense>,day:LocalDate=LocalDate.now())=items.filter{it.day==day.toString()}.sumOf{it.amount}
    fun remaining(budget:Int,spent:Int)=budget-spent
    fun week(items:List<Expense>,day:LocalDate=LocalDate.now())=items.filter { !LocalDate.parse(it.day).isBefore(day.minusDays(6)) && !LocalDate.parse(it.day).isAfter(day) }
    fun tip(items:List<Expense>):String {
        val frequent=items.groupBy{it.item.lowercase()}.filter { (key,v)->key !in listOf("transportation","bills","other") && v.size>=2 }.maxByOrNull{it.value.sumOf{e->e.amount}} ?: return "Record more purchases to discover spending patterns."
        val name=frequent.value.first().item
        val total=frequent.value.sumOf{it.amount}
        return "You spent ₱"+total+" on "+name+" across "+frequent.value.size+" purchases this week. Try buying it less often to save."
    }
}
