package com.rudev.wateringcanplus.interactions;

import com.hypixel.hytale.builtin.adventure.farming.interactions.UseWateringCanInteraction;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

public class ImprovedUseWateringCanInteraction extends UseWateringCanInteraction {

    protected int expansionAmount;
    protected int subtractionAmount;

    @Nonnull
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void interactWithBlock(@NonNullDecl World world, @NonNullDecl CommandBuffer<EntityStore> commandBuffer, @NonNullDecl InteractionType type, @NonNullDecl InteractionContext context, @NullableDecl ItemStack itemInHand, @NonNullDecl Vector3i targetBlock, @NonNullDecl CooldownHandler cooldownHandler) {
        //super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);

        List<TilledSoilBlock> tilledSoilBlockList = new ArrayList<TilledSoilBlock>();
        List<Vector3i> blockPositions = new ArrayList<Vector3i>();

        int absoluteExpansionAmount = Math.abs(expansionAmount);
        int absoluteSubtractionAmount = Math.abs(subtractionAmount);

        // Farming state on the og one was to see if there was a soil below whatever was growing, make sure to include that
        // for now it will only work in just the soil
        for (int x_offset = -absoluteExpansionAmount; x_offset <= absoluteExpansionAmount - absoluteSubtractionAmount; x_offset++) {
            for (int z_offset = -absoluteExpansionAmount; z_offset <= absoluteExpansionAmount - absoluteSubtractionAmount; z_offset++) {

                int x = targetBlock.getX() + x_offset;
                int y = targetBlock.getY();
                int z = targetBlock.getZ() + z_offset;

                WorldChunk worldChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                Ref<ChunkStore> blockRef = worldChunk.getBlockComponentEntity(x, y, z);

                if (blockRef == null) {
                    blockRef = BlockModule.ensureBlockEntity(worldChunk, targetBlock.x, targetBlock.y, targetBlock.z);
                }
                else {
                    Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
                    TilledSoilBlock soil = (TilledSoilBlock)chunkStore.getComponent(blockRef, TilledSoilBlock.getComponentType());

                    if (soil != null) {
                        tilledSoilBlockList.add(soil);
                        blockPositions.add(new Vector3i(x, y, z));
                    }
                    else {
                        FarmingBlock farmingState = (FarmingBlock)chunkStore.getComponent(blockRef, FarmingBlock.getComponentType());
                        if (farmingState != null) {
                            Ref<ChunkStore> soilRef = worldChunk.getBlockComponentEntity(x, y - 1, z);
                            if (soilRef != null) {
                                TilledSoilBlock belowSoil = (TilledSoilBlock)chunkStore.getComponent(soilRef, TilledSoilBlock.getComponentType());
                                if (belowSoil != null){
                                    tilledSoilBlockList.add(belowSoil);
                                    blockPositions.add(new Vector3i(x, y - 1, z));
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!tilledSoilBlockList.isEmpty()){
            WorldTimeResource worldTimeResource = (WorldTimeResource)commandBuffer.getResource(WorldTimeResource.getResourceType());

            for (int i = 0; i < tilledSoilBlockList.size(); i++) {
                TilledSoilBlock soilBlock = tilledSoilBlockList.get(i);
                Vector3i targetBlockPosition = blockPositions.get(i);

                WorldChunk worldChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(targetBlockPosition.x, targetBlockPosition.z));
                Instant wateredUntil = worldTimeResource.getGameTime().plus(this.duration, ChronoUnit.SECONDS);
                soilBlock.setWateredUntil(wateredUntil);

                // Tick the soil itself
                worldChunk.setTicking(targetBlockPosition.x, targetBlockPosition.y, targetBlockPosition.z, true);
                worldChunk.getBlockChunk().getSectionAtBlockY(targetBlockPosition.y).scheduleTick(ChunkUtil.indexBlock(targetBlockPosition.x, targetBlockPosition.y, targetBlockPosition.z), wateredUntil);

                // Tick the thing growing on top
                worldChunk.setTicking(targetBlockPosition.x, targetBlockPosition.getY() + 1, targetBlockPosition.z, true);
            }

            context.getState().state = InteractionState.Finished;
        }
        else {
            context.getState().state = InteractionState.Failed;
        }
    }

    public static final BuilderCodec<ImprovedUseWateringCanInteraction> CODEC = BuilderCodec.builder(
                    ImprovedUseWateringCanInteraction.class, ImprovedUseWateringCanInteraction::new, SimpleBlockInteraction.CODEC
            )
            .documentation("Waters the target farmable block in an area.")
            .addField(new KeyedCodec<>("Duration", Codec.LONG), (interaction, duration) -> interaction.duration = duration, interaction -> interaction.duration)
            .addField(new KeyedCodec<>("Expansion", Codec.INTEGER), (interaction, expansionAmount) -> interaction.expansionAmount = expansionAmount, interaction -> interaction.expansionAmount)
            .addField(new KeyedCodec<>("Subtraction", Codec.INTEGER), (interaction, subtractionAmount) -> interaction.subtractionAmount = subtractionAmount, interaction -> interaction.subtractionAmount)
            .addField(
                    new KeyedCodec<>("RefreshModifiers", Codec.STRING_ARRAY),
                    (interaction, refreshModifiers) -> interaction.refreshModifiers = refreshModifiers,
                    interaction -> interaction.refreshModifiers
            )
            .build();
}
