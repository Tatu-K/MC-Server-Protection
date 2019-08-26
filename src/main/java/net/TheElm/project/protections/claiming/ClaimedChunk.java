/*
 * This software is licensed under the MIT License
 * https://github.com/GStefanowich/MC-Server-Protection
 *
 * Copyright (c) 2019 Gregory Stefanowich
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.TheElm.project.protections.claiming;

import net.TheElm.project.CoreMod;
import net.TheElm.project.MySQL.MySQLStatement;
import net.TheElm.project.config.SewingMachineConfig;
import net.TheElm.project.enums.ClaimPermissions;
import net.TheElm.project.enums.ClaimRanks;
import net.TheElm.project.enums.ClaimSettings;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClaimedChunk {
    
    private UUID chunkTown   = null;
    private UUID chunkPlayer = null;
    
    private final int world;
    private final int x;
    private final int z;
    
    private ClaimedChunk(@NotNull WorldChunk worldChunk) throws SQLException {
        // Get the chunk details
        this.world = worldChunk.getWorld().getDimension().getType().getRawId();
        this.x = worldChunk.getPos().x;
        this.z = worldChunk.getPos().z;
        
        try (MySQLStatement stmt = CoreMod.getSQL().prepare("SELECT `chunkOwner`, `chunkTown` FROM `chunk_Claimed` WHERE `chunkX` = ? AND `chunkZ` = ? AND `chunkWorld` = ?;")) {
            
            // Check the database for the chunk owner
            stmt.addPrepared(this.getX())
                .addPrepared(this.getZ())
                .addPrepared(this.getWorld());
            
            try ( ResultSet rs = stmt.executeStatement() ) {
                while (rs.next()) {
                    this.updatePlayerOwner(UUID.fromString(rs.getString("chunkOwner")));
                    if (rs.getString("chunkTown") != null)
                        this.updateTownOwner(UUID.fromString(rs.getString("chunkTown")));
                }
            }
        }
    }
    
    public int getX() {
        return this.x;
    }
    public int getZ() {
        return this.z;
    }
    
    public ChunkPos getChunkPos() {
        return new ChunkPos( this.x, this.z );
    }
    public BlockPos getBlockPos() {
        return new BlockPos( this.x << 4, 0, this.z << 4 );
    }
    
    public int getWorld() {
        return this.world;
    }
    
    public void updateTownOwner(@Nullable UUID owner) {
        this.chunkTown = owner;
        
        // Make sure we have the towns permissions cached
        if (owner != null)
            ClaimantTown.get( owner );
    }
    public void updatePlayerOwner(@Nullable UUID owner) {
        this.chunkPlayer = owner;
        
        // Make sure we have the players permissions cached
        if (owner != null)
            ClaimantPlayer.get( this.chunkPlayer );
        else 
            this.updateTownOwner( null );
    }
    
    @Nullable
    public UUID getOwner() {
        return this.chunkPlayer;
    }
    @Nullable
    public ClaimantTown getTown() {
        if ( this.chunkTown == null ) {
            if ( this.chunkPlayer == null )
                return null;
            ClaimantPlayer claimaint = ClaimantPlayer.get( this.chunkPlayer );
            if ( claimaint.getTown() != null ) {
                ClaimantTown town = ClaimantTown.get( claimaint.getTown() );
                if ( this.chunkPlayer.equals( town.getOwner() ) )
                    this.updateTownOwner(claimaint.getTown());
                return town;
            }
            return null;
        }
        ClaimantTown town = ClaimantTown.get( this.chunkTown );
        if ( town == null )
            this.updateTownOwner( null );
        return town;
    }
    
    public Text getOwnerName(@NotNull PlayerEntity zonePlayer) {
        ClaimantPlayer permissions;
        if ( this.chunkPlayer == null || ((permissions = ClaimantPlayer.get( this.chunkPlayer )) == null))
            return new LiteralText(SewingMachineConfig.INSTANCE.NAME_WILDERNESS.get())
                .formatted(Formatting.GREEN);
        
        // Get the owners name
        return permissions.getName( zonePlayer.getUuid() );
    }
    
    public boolean canUserDo(@NotNull UUID player, ClaimPermissions perm) {
        if ( ( this.chunkPlayer == null ) || player.equals( this.chunkPlayer ) )
            return true;
        if ( ( this.getTown() != null ) && player.equals( this.getTown().getOwner() ) )
            return true;
        
        // Check our chunk permissions
        ClaimantPlayer permissions = ClaimantPlayer.get( this.chunkPlayer );
        if ( permissions == null ) return false; // Permissions should not be NULL unless something is wrong
        
        // Get the ranks of the user and the rank required for performing
        ClaimRanks userRank = permissions.getFriendRank( player );
        ClaimRanks permReq = permissions.getPermissionRankRequirement( perm );
        
        // Return the test if the user can perform the action
        return permReq.canPerform( userRank );
    }
    public boolean isSetting(@NotNull ClaimSettings setting) {
        boolean permission;
        if ( this.chunkPlayer == null )
            permission = setting.getPlayerDefault();
        else 
            permission = ClaimantPlayer.get( this.chunkPlayer)
                .getProtectedChunkSetting( setting );
        return permission;
    }
    
    // Read from database (Can be a null result)
    @Nullable
    public static ClaimedChunk convert(World world, BlockPos blockPos) {
        return ClaimedChunk.convert(world.getWorldChunk(blockPos));
    }
    @Nullable
    public static ClaimedChunk convert(WorldChunk worldChunk) {
        // If claims are disabled
        if (!SewingMachineConfig.INSTANCE.DO_CLAIMS.get()) return null;
        
        try {
            
            return ClaimedChunk.convertNonNull( worldChunk );
            
        } catch (SQLException e) {
            CoreMod.logError( e );
        }
        
        return null;
    }
    // Read from database (Result should be NULL or EXCEPTION)
    public static ClaimedChunk convertNonNull(WorldChunk worldChunk) throws SQLException {
        // Get the cached claim
        ClaimedChunk chunk;
        if ((chunk = ClaimedChunk.convertFromCache(worldChunk)) != null)
            return chunk;

        // Save this chunk to the cache (To be delisted when the chunk unloads)
        chunk = new ClaimedChunk(worldChunk);
        CoreMod.CHUNK_CACHE.put(worldChunk, chunk);

        return chunk;
    }
    public static ClaimedChunk convertNonNull(World world, BlockPos blockPos) throws SQLException {
        return ClaimedChunk.convertNonNull( world.getWorldChunk( blockPos ) );
    }
    // Read from cache
    @Nullable
    public static ClaimedChunk convertFromCache(WorldChunk worldChunk) {
        if ( CoreMod.CHUNK_CACHE.containsKey( worldChunk ) )
            return CoreMod.CHUNK_CACHE.get( worldChunk );
        return null;
    }
    
    public static boolean isOwnedAround( World world, BlockPos blockPos, int leniency ) {
        return ClaimedChunk.getOwnedAround( world, blockPos, leniency).length > 0;
    }
    public static ClaimedChunk[] getOwnedAround(final World world, final BlockPos blockPos, final int leniency) {
        int chunkX = blockPos.getX() >> 4;
        int chunkZ = blockPos.getZ() >> 4;
        
        List<ClaimedChunk> claimedChunks = new ArrayList<>();
        try (MySQLStatement statement = CoreMod.getSQL().prepare("SELECT `chunkOwner`, `chunkX`, `chunkZ` FROM `chunk_Claimed` WHERE `chunkX` >= ? - " + leniency + " AND `chunkX` <= ? + " + leniency + " AND `chunkZ` >= ? - " + leniency + " AND `chunkZ` <= ? +" + leniency + " AND `chunkWorld` = ?", false)
            .addPrepared(chunkX)
            .addPrepared(chunkX)
            .addPrepared(chunkZ)
            .addPrepared(chunkZ)
            .addPrepared(world.dimension.getType().getRawId())) {
            
            ResultSet resultSet = statement.executeStatement();
            while (resultSet.next()) {
                ClaimedChunk chunk = new ClaimedChunk(world.method_8497( resultSet.getInt("chunkX"), resultSet.getInt("chunkZ") ));
                chunk.updatePlayerOwner(UUID.fromString(resultSet.getString("chunkOwner")));
                claimedChunks.add( chunk );
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
            
        }
        
        return claimedChunks.toArray(new ClaimedChunk[0]);
    }
}
