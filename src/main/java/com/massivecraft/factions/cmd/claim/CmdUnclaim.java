package com.massivecraft.factions.cmd.claim;

import com.massivecraft.factions.*;
import com.massivecraft.factions.cmd.Aliases;
import com.massivecraft.factions.cmd.CommandContext;
import com.massivecraft.factions.cmd.CommandRequirements;
import com.massivecraft.factions.cmd.FCommand;
import com.massivecraft.factions.event.LandUnclaimEvent;
import com.massivecraft.factions.integration.Econ;
import com.massivecraft.factions.struct.Permission;
import com.massivecraft.factions.struct.Role;
import com.massivecraft.factions.util.ChunkReference;
import com.massivecraft.factions.util.FastChunk;
import com.massivecraft.factions.util.SpiralTask;
import com.massivecraft.factions.zcore.fperms.Access;
import com.massivecraft.factions.zcore.fperms.PermissableAction;
import com.massivecraft.factions.zcore.util.TL;
import org.bukkit.Bukkit;

public class CmdUnclaim extends FCommand {

    /**
     * @author FactionsUUID Team - Modified By CmdrKittens
     */

    public CmdUnclaim() {
        this.aliases.addAll(Aliases.unclaim_unclaim);

        this.optionalArgs.put("radius", "1");
        this.optionalArgs.put("faction", "yours");

        this.requirements = new CommandRequirements.Builder(Permission.UNCLAIM)
                .playerOnly()
                .withAction(PermissableAction.TERRITORY)
                .build();
    }

    @Override
    public void perform(final CommandContext context) {
        // Read and validate input
        int radius = context.argAsInt(0, 1); // Default to 1
        final Faction forFaction = context.argAsFaction(1, context.faction); // Default to own

        if (radius < 1) {
            context.msg(TL.COMMAND_CLAIM_INVALIDRADIUS);
            return;
        }

        if (radius < 2) {
            // single chunk
            unClaim(new FLocation(context.player), context, forFaction);
        } else {
            // radius claim
            if (!Permission.CLAIM_RADIUS.has(context.sender, false)) {
                context.msg(TL.COMMAND_CLAIM_DENIED);
                return;
            }

            new SpiralTask(new FLocation(context.player), radius) {
                private final int limit = Conf.radiusClaimFailureLimit - 1;
                private int failCount = 0;

                @Override
                public boolean work() {
                    boolean success = unClaim(this.currentFLocation(), context, forFaction);
                    if (success) {
                        failCount = 0;
                    } else if (failCount++ >= limit) {
                        this.stop();
                        return false;
                    }

                    return true;
                }
            };
        }
    }

    private boolean unClaim(FLocation target, CommandContext context, Faction faction) {
        Faction targetFaction = Board.getInstance().getFactionAt(target);

        if (context.faction != targetFaction && !context.fPlayer.isAdminBypassing()) {
            context.msg(TL.COMMAND_UNCLAIM_WRONGFACTION);
            return false;
        }

        if (targetFaction.isSafeZone()) {
            if (Permission.MANAGE_SAFE_ZONE.has(context.sender)) {
                Board.getInstance().removeAt(target);
                context.msg(TL.COMMAND_UNCLAIM_SAFEZONE_SUCCESS);

                if (Conf.logLandUnclaims) {
                    FactionsPlugin.getInstance().log(TL.COMMAND_UNCLAIM_LOG.format(context.fPlayer.getName(), target.getCoordString(), targetFaction.getTag()));
                }
                return true;
            } else {
                context.msg(TL.COMMAND_UNCLAIM_SAFEZONE_NOPERM);
                return false;
            }
        } else if (targetFaction.isWarZone()) {
            if (Permission.MANAGE_WAR_ZONE.has(context.sender)) {
                Board.getInstance().removeAt(target);
                context.msg(TL.COMMAND_UNCLAIM_WARZONE_SUCCESS);

                if (Conf.logLandUnclaims) {
                    FactionsPlugin.getInstance().log(TL.COMMAND_UNCLAIM_LOG.format(context.fPlayer.getName(), target.getCoordString(), targetFaction.getTag()));
                }
                return true;
            } else {
                context.msg(TL.COMMAND_UNCLAIM_WARZONE_NOPERM);
                return false;
            }
        }

        if (context.fPlayer.isAdminBypassing()) {
            LandUnclaimEvent unclaimEvent = new LandUnclaimEvent(target, targetFaction, context.fPlayer);
            Bukkit.getServer().getPluginManager().callEvent(unclaimEvent);
            if (unclaimEvent.isCancelled()) {
                return false;
            }

            Board.getInstance().removeAt(target);

            targetFaction.msg(TL.COMMAND_UNCLAIM_UNCLAIMED, context.fPlayer.describeTo(targetFaction, true));
            context.msg(TL.COMMAND_UNCLAIM_UNCLAIMS);

            if (Conf.logLandUnclaims) {
                FactionsPlugin.getInstance().log(TL.COMMAND_UNCLAIM_LOG.format(context.fPlayer.getName(), target.getCoordString(), targetFaction.getTag()));
            }

            return true;
        }

        if (!context.assertHasFaction()) {
            return false;
        }

        if (targetFaction.getAccess(context.fPlayer, PermissableAction.TERRITORY) == Access.DENY && context.fPlayer.getRole() != Role.LEADER) {
            context.msg(TL.GENERIC_FPERM_NOPERMISSION, "unclaim");
            return false;
        }

        if (context.faction != targetFaction && !context.fPlayer.isAdminBypassing()) {
            context.msg(TL.COMMAND_UNCLAIM_WRONGFACTION);
            return false;
        }

        LandUnclaimEvent unclaimEvent = new LandUnclaimEvent(target, targetFaction, context.fPlayer);
        Bukkit.getServer().getPluginManager().callEvent(unclaimEvent);
        if (unclaimEvent.isCancelled()) {
            return false;
        }

        if (Econ.shouldBeUsed()) {
            double refund = Econ.calculateClaimRefund(context.faction.getLandRounded());

            if (Conf.bankEnabled && Conf.bankFactionPaysLandCosts) {
                if (!Econ.modifyMoney(context.faction, refund, TL.COMMAND_UNCLAIM_TOUNCLAIM.toString(), TL.COMMAND_UNCLAIM_FORUNCLAIM.toString())) {
                    return false;
                }
            } else {
                if (!Econ.modifyMoney(context.fPlayer, refund, TL.COMMAND_UNCLAIM_TOUNCLAIM.toString(), TL.COMMAND_UNCLAIM_FORUNCLAIM.toString())) {
                    return false;
                }
            }
        }

        FastChunk fc = new FastChunk(target.getWorldName(), target.getChunk().getX(), target.getChunk().getZ());
        if(Conf.userSpawnerChunkSystem) {
            if (faction.getSpawnerChunks().contains(fc) && faction.getSpawnerChunks() != null) {
                if (Conf.allowUnclaimSpawnerChunksWithSpawnersInChunk) {
                    context.faction.getSpawnerChunks().remove(fc);
                } else {
                    if (ChunkReference.getSpawnerCount(target.getChunk()) > 0) {
                        context.fPlayer.msg(TL.COMMAND_UNCLAIM_SPAWNERCHUNK_SPAWNERS, ChunkReference.getSpawnerCount(target.getChunk()));
                    }
                    return false;
                }
            }
        }

        Board.getInstance().removeAt(target);

        context.faction.msg(TL.COMMAND_UNCLAIM_FACTIONUNCLAIMED, context.fPlayer.describeTo(context.faction, true));

        if (Conf.logLandUnclaims) {
            FactionsPlugin.getInstance().log(TL.COMMAND_UNCLAIM_LOG.format(context.fPlayer.getName(), target.getCoordString(), targetFaction.getTag()));
        }

        return true;
    }

    @Override
    public TL getUsageTranslation() {
        return TL.COMMAND_UNCLAIM_DESCRIPTION;
    }

}
