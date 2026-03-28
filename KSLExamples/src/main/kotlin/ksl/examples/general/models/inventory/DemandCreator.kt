package ksl.examples.general.models.inventory

import ksl.simulation.ModelElement

abstract class DemandCreator(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name){

    /**
     * Represents a demand created by the demand creator. The demand has an original amount, which is the amount of
     * demand that was originally created, and an amount needed, which is the amount of demand that still needs to be
     * filled. The demand also has a demand sender, which is the entity that sent the demand,
     * and a demand receiver, which is the entity that received the demand. The demand also has a time filled,
     * which is the time at which the demand was filled.
     * The demand is filled by calling the fill method, which takes the amount supplied as an argument.
     * The fill method updates the amount filled and the amount needed, and if the demand is filled, it updates the time filled.
     * @param originalAmount the original amount of demand that was created. This must be > 0.
     * @param filledDemandReceiver the demand receiver that is the final customer for this demand. This receiver should
     * receive filled demand and use it to replenish on-hand inventory.
     */
    inner class Demand(
        val itemType: ItemType,
        @Suppress("Unused")
        val originalAmount: Int,
        val filledDemandReceiver: InventoryReceiverIfc
    ) : QObject() {
        init {
            require(originalAmount > 0) { "The original demand amount must be > 0" }
        }

        /** The demand sender that sent this demand. This is useful for the demand to know where the demand came from.
         * Note that the demand sender may be different from the demand creator if the demand was sent
         * by a different entity than the one that created it.
         * If this property is set, then its value should reflect the **last** DemandSenderIfc instance to have
         * sent the demand to a receiver.
         */
//        var demandSender: DemandSenderIfc? = null
//            internal set

        /**
         * The demand receiver that received this demand. This is useful to know where the demand was sent to.
         * If set, this property should reflect the **last** DemandReceiverIfc instance that received the demand.
         */
        @Suppress("Unused")
//        var demandReceiver: DemandReceiverIfc? = null
//            internal set

        /** The amount of demand that still needs to be filled. This is initialized to the original amount,
         * and is updated when the demand is filled.
         * Note that the amount needed can never be negative, because the fill method checks that the amount supplied
         * cannot be greater than the amount needed.
         */
        var amountNeeded: Int = originalAmount
            private set(value) {
                require(value >= 0) { "The demand amount needed must be >= 0" }
                field = value
            }

        /** The amount of demand that has been filled. This is initialized to 0, and is updated when the demand is filled.
         * Note that the amount filled can never be negative, because the fill method checks that the amount supplied
         * must be > 0, and the amount supplied is added to the amount filled.
         */
        var amountFilled: Int = 0
            private set(value) {
                require(value >= 0) { "The demand amount filled must be >= 0" }
                field = value
            }

        /**
         * Returns true if the demand is filled, which is when the amount needed is 0.
         * Note that the demand is filled when the amount needed is 0
         * because it is possible that the demand was partially filled, in which case, the amount filled would be less than
         * the original amount.
         */
        val isFilled: Boolean
            get() = amountNeeded == 0

        /**
         *  Indicates that the demand is not filled
         */
        val isNotFilled: Boolean
            get() = !isFilled
        /**
         * The time at which the demand was filled. This is initialized to positive infinity, and is updated when the demand is filled.
         */
        var timeFilled: Double = Double.POSITIVE_INFINITY
            private set

        /** Fills the demand with the given amount supplied. The amount supplied must be > 0 and <= the amount needed.
         * The method updates the amount filled and the amount needed, and if the demand is filled, it updates the time filled.
         */
        @Suppress("Unused")
        fun fill(amountSupplied: Int) {
            require(!isFilled) { "The demand is already filled." }
            require(amountSupplied > 0) { "The amount supplied must be > 0" }
            require(amountSupplied <= amountNeeded) { "The amount supplied cannot be greater than the amount needed." }
            amountFilled = amountFilled + amountSupplied
            amountNeeded = amountNeeded - amountSupplied
            if (isFilled) {
                timeFilled = time
            }
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.appendLine("Demand:")
            sb.appendLine(" itemType=$itemType")
            sb.appendLine(" originalAmount=$originalAmount")
            sb.appendLine(" amountNeeded=$amountNeeded")
            sb.appendLine(" amountFilled=$amountFilled")
            sb.appendLine(" timeFilled=$timeFilled")
            sb.appendLine(" isFilled=$isFilled")
            sb.appendLine(" customer=${this@Demand.filledDemandReceiver}")
//            sb.appendLine(" sender=${demandSender}")
//            sb.appendLine(" receiver=${demandReceiver}")
            return sb.toString()
        }
    }

}