package xyz.xenondevs.nova.util

import net.kyori.adventure.text.Component
import net.minecraft.advancements.Advancement
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.advancements.AdvancementRequirements
import net.minecraft.advancements.Criterion
import net.minecraft.advancements.DisplayInfo
import net.minecraft.advancements.FrameType
import net.minecraft.advancements.critereon.InventoryChangeTrigger
import net.minecraft.advancements.critereon.ItemPredicate
import net.minecraft.commands.arguments.ObjectiveCriteriaArgument.criteria
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import xyz.xenondevs.nova.addon.Addon
import xyz.xenondevs.nova.item.NovaItem
import xyz.xenondevs.nova.util.component.adventure.toNMSComponent

fun advancement(addon: Addon, name: String, init: Advancement.Builder.() -> Unit): AdvancementHolder {
    val builder = Advancement.Builder()
    builder.init()
    return builder.build(ResourceLocation("${addon.description.id}:$name"))
}

fun obtainNovaItemAdvancement(
    addon: Addon,
    parent: AdvancementHolder?,
    item: NovaItem,
    frameType: FrameType = FrameType.TASK
): AdvancementHolder {
    require(addon.description.id == item.id.namespace) { "The specified item is from a different addon" }
    val id = item.id
    return advancement(addon, "obtain_${id.name}") {
        if (parent != null)
            parent(parent)
        
        display(DisplayInfo(
            item.clientsideProvider.get().nmsCopy,
            Component.translatable("advancement.${id.namespace}.${id.name}.title").toNMSComponent(),
            Component.translatable("advancement.${id.namespace}.${id.name}.description").toNMSComponent(),
            null,
            frameType,
            true, true, false
        ))
        
        addCriterion("obtain_${id.name}", createObtainNovaItemCriterion(item))
    }
}

fun obtainNovaItemsAdvancement(
    addon: Addon,
    name: String,
    parent: AdvancementHolder?,
    items: List<NovaItem>, requireAll: Boolean,
    frameType: FrameType = FrameType.TASK
): AdvancementHolder {
    require(items.all { it.id.namespace == addon.description.id }) { "At least one of the specified items is from a different addon" }
    val namespace = addon.description.id
    return advancement(addon, name) {
        if (parent != null)
            parent(parent)
        
        display(DisplayInfo(
            items[0].clientsideProvider.get().nmsCopy,
            Component.translatable("advancement.$namespace.$name.title").toNMSComponent(),
            Component.translatable("advancement.$namespace.$name.description").toNMSComponent(),
            null,
            frameType,
            true, true, false
        ))
        
        val criteriaNames = ArrayList<String>()
        
        for (item in items) {
            val criterionName = "obtain_${item.id.name}"
            addCriterion(criterionName, createObtainNovaItemCriterion(item))
        }
        
        if (requireAll) {
            requirements(AdvancementRequirements.allOf(criteriaNames))
        } else {
            requirements(AdvancementRequirements.anyOf(criteriaNames))
        }
    }
}

private fun createObtainNovaItemCriterion(item: NovaItem): Criterion<InventoryChangeTrigger.TriggerInstance> {
    val nbt = CompoundTag()
    val nova = CompoundTag()
    nbt.put("nova", nova)
    nova.putString("id", item.id.toString())
    return InventoryChangeTrigger.TriggerInstance.hasItems(ItemPredicate.Builder.item().hasNbt(nbt))
}

fun Player.awardAdvancement(key: NamespacedKey) {
    val advancement = Bukkit.getAdvancement(key)
    if (advancement != null) {
        val progress = getAdvancementProgress(advancement)
        advancement.criteria.forEach { progress.awardCriteria(it) }
    }
}