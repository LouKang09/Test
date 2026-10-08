package com.tracker.offline

import java.time.LocalDate

data class ParsedExpense(val amount:Int,val category:String,val item:String)

object CommandParser {
    private val money = Regex("""(?i)(?:₱\s*|php\s*|p\s*)([0-9][0-9,]*)|([0-9][0-9,]*)\s*(?:pesos?|php|₱)""")
    private val anyNumber = Regex("""\b([0-9][0-9,]*)\b""")
    private val categories = linkedMapOf(
        "Transportation" to listOf("transport", "transportation", "jeep", "bus", "taxi", "grab", "fare", "pamasahe", "tricycle", "gasoline"),
        "Food" to listOf("food", "snack", "meal", "lunch", "dinner", "breakfast", "ate", "kain", "ulam", "coke", "coffee", "drink", "merienda", "bumili"),
        "Bills" to listOf("bill", "rent", "electric", "water", "internet", "load", "payment"),
        "Shopping" to listOf("shopping", "clothes", "grocery", "groceries"),
        "Health" to listOf("medicine", "hospital", "doctor", "health"))
    fun parse(input:String): ParsedExpense? {
        val t=input.trim().lowercase()
        val match=money.find(t)
        val raw=match?.groupValues?.drop(1)?.firstOrNull{it.isNotBlank()} ?: anyNumber.find(t)?.groupValues?.get(1) ?: return null
        val amount=raw.replace(",","").toIntOrNull()?.takeIf{it>0} ?: return null
        val explicit = Regex("""(?:as|under|category|sa)\s+(transportation|transport|food|bills|shopping|health|other)""").find(t)?.groupValues?.get(1)
        val category = when(explicit) {"transport","transportation"->"Transportation";"food"->"Food";"bills"->"Bills";"shopping"->"Shopping";"health"->"Health";"other"->"Other";else->categories.entries.firstOrNull { (_, words)->words.any { Regex("""\b"""+Regex.escape(it)+"""\b""").containsMatchIn(t) } }?.key ?: "Other"}
        val item = when {
            "coke" in t -> "Coke"
            "coffee" in t -> "Coffee"
            "snack" in t -> "Snacks"
            "jeep" in t -> "Jeepney"
            "bus" in t -> "Bus"
            "lunch" in t -> "Lunch"
            "dinner" in t -> "Dinner"
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
        val sample=frequent.value.first().item
        val total=frequent.value.sumOf{it.amount}
        return "You spent ₱$total on $sample across ${frequent.value.size} purchases in the past 7 days. Consider reducing non-essential purchases."
    }
}
