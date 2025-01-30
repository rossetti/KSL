package ksl.examples.general.lectures.week5

class Person (someName: String, someHeight: Double, someColor: String = defaultHairColor) {
    val id = ++countPeople
    val name = someName
    val height = someHeight
    var hairColor: String = someColor

    override fun toString(): String {
        return "Person(id = $id, name='$name', height=$height, hair color='$hairColor')"
    }

    fun print(){
        println(this.toString())
    }

    companion object {
        var countPeople = 0
            private set

        var defaultHairColor = "Brown"
    }

}