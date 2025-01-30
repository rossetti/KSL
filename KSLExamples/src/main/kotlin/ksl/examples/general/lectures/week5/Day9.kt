package ksl.examples.general.lectures.week5

fun main(){

    val p1 = Person("Manuel", 72.0, "black")
    val p2 = Person("Joseph", 72.0)
    val p3 = Person("Maria", 66.0)
    val p4 = Person("Amy", 66.0)

    p1.print()
    p2.print()
    p4.hairColor = "blue"
    val family = mutableListOf(p1, p2, p3)
    family.add(p4)
    for(person in family){
        person.print()
    }
}