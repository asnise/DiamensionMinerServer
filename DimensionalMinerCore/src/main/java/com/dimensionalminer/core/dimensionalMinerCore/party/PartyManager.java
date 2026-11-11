package com.dimensionalminer.core.dimensionalMinerCore.party;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartyManager {

    // Variables/Fields
    private final DimensionalMinerCore plugin;
    private final Map<UUID, PartySession> partyById = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToParty = new ConcurrentHashMap<>();
    private final Map<UUID, PartyInvite> pendingInvites = new ConcurrentHashMap<>();

    // Constructors
    public PartyManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    // Public Methods
    public void createParty(Player leader) {
        UUID playerId = leader.getUniqueId();
        if (playerToParty.containsKey(playerId)) {
            leader.sendMessage(ChatColor.RED + "You are already in a party. Leave it first.");
            return;
        }

        UUID partyId = UUID.randomUUID();
        PartySession session = new PartySession(partyId, playerId);
        partyById.put(partyId, session);
        playerToParty.put(playerId, partyId);

        leader.sendMessage(ChatColor.GREEN + "[Party] Party created! You are the leader.");
        leader.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.AQUA + "/party invite <player>" + ChatColor.YELLOW + " to invite teammates.");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID leaderAccountId = plugin.getDatabaseManager().getAccountId(playerId);
                savePartyDb(partyId, leaderAccountId);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to persist party: " + e.getMessage());
            }
        });
    }

    public void invitePlayer(Player leader, Player target) {
        UUID leaderId = leader.getUniqueId();
        UUID partyId = playerToParty.get(leaderId);
        if (partyId == null) {
            leader.sendMessage(ChatColor.RED + "You must create a party first (/party create).");
            return;
        }

        PartySession session = partyById.get(partyId);
        if (session == null || !session.getLeaderId().equals(leaderId)) {
            leader.sendMessage(ChatColor.RED + "Only the party leader can invite players.");
            return;
        }
        if (isLockedByDungeon(partyId)) {
            leader.sendMessage(ChatColor.RED + "[Party Lock] Your party is in a dungeon run. Finish or leave it first.");
            return;
        }

        if (session.getMembers().size() >= 4) {
            leader.sendMessage(ChatColor.RED + "Your party is already full (Max 4 players).");
            return;
        }

        UUID targetId = target.getUniqueId();
        if (playerToParty.containsKey(targetId)) {
            leader.sendMessage(ChatColor.RED + target.getName() + " is already in a party.");
            return;
        }

        pendingInvites.put(targetId, new PartyInvite(partyId, leaderId, System.currentTimeMillis() + 60000));
        leader.sendMessage(ChatColor.GREEN + "[Party] Invite sent to " + target.getName() + ".");
        target.sendMessage(ChatColor.GOLD + "========================================");
        target.sendMessage(ChatColor.YELLOW + leader.getName() + ChatColor.GREEN + " invited you to join their party!");
        target.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.AQUA + "/party accept " + leader.getName() + ChatColor.YELLOW + " or " + ChatColor.RED + "/party decline");
        target.sendMessage(ChatColor.GOLD + "========================================");
    }

    public void acceptInvite(Player player) {
        UUID playerId = player.getUniqueId();
        PartyInvite invite = pendingInvites.remove(playerId);
        if (invite == null || invite.isExpired()) {
            player.sendMessage(ChatColor.RED + "[Party] You have no active party invites or it has expired.");
            return;
        }

        // A player belongs to at most one party (they may have created their own after being invited).
        if (playerToParty.containsKey(playerId)) {
            player.sendMessage(ChatColor.RED + "[Party] You are already in a party. Leave it before accepting another invite.");
            return;
        }

        UUID partyId = invite.partyId();
        PartySession session = partyById.get(partyId);
        if (session == null || session.getMembers().size() >= 4) {
            player.sendMessage(ChatColor.RED + "[Party] The party is no longer available or is full.");
            return;
        }
        if (isLockedByDungeon(partyId)) {
            player.sendMessage(ChatColor.RED + "[Party Lock] That party is in a dungeon run right now. Try again after it ends.");
            return;
        }

        session.addMember(playerId);
        playerToParty.put(playerId, partyId);
        broadcastParty(partyId, ChatColor.GREEN + "[Party] " + player.getName() + " joined the party!");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID accountId = plugin.getDatabaseManager().getAccountId(playerId);
                addMemberDb(partyId, accountId);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save party member: " + e.getMessage());
            }
        });
    }

    public void leaveParty(Player player) {
        UUID playerId = player.getUniqueId();
        UUID current = playerToParty.get(playerId);
        if (current != null && isLockedByDungeon(current)) {
            // Party is frozen while it has a run (spec 6.2), for every command alias. Someone who
            // already left the run with /exitdungeon may leave the party, except the leader
            // (leaving would disband the party the run belongs to).
            boolean inRun = plugin.getDungeonInstanceManager().getSessionByPlayer(playerId).isPresent();
            PartySession ps = partyById.get(current);
            boolean isLeader = ps != null && ps.getLeaderId().equals(playerId);
            if (inRun || isLeader) {
                player.sendMessage(ChatColor.RED + "[Party Lock] You cannot leave or disband your party during a dungeon run.");
                player.sendMessage(ChatColor.YELLOW + (inRun
                        ? "Use /exitdungeon first, or wait until the run ends."
                        : "Wait until the run ends."));
                return;
            }
        }
        UUID partyId = playerToParty.remove(playerId);
        if (partyId == null) {
            player.sendMessage(ChatColor.RED + "You are not in a party.");
            return;
        }

        PartySession session = partyById.get(partyId);
        if (session == null) return;

        session.removeMember(playerId);
        player.sendMessage(ChatColor.YELLOW + "[Party] You left the party.");
        broadcastParty(partyId, ChatColor.RED + "[Party] " + player.getName() + " left the party.");

        if (session.getMembers().isEmpty() || session.getLeaderId().equals(playerId)) {
            disbandParty(partyId);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    UUID accountId = plugin.getDatabaseManager().getAccountId(playerId);
                    removeMemberDb(partyId, accountId);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to remove party member from DB: " + e.getMessage());
                }
            });
        }
    }

    public void disbandParty(UUID partyId) {
        PartySession session = partyById.remove(partyId);
        if (session != null) {
            for (UUID mId : session.getMembers()) {
                playerToParty.remove(mId);
                Player p = Bukkit.getPlayer(mId);
                if (p != null && p.isOnline()) {
                    p.sendMessage(ChatColor.RED + "[Party] The party has been disbanded.");
                }
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    disbandPartyDb(partyId);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to record party disband in DB: " + e.getMessage());
                }
            });
        }
    }

    public void broadcastParty(UUID partyId, String message) {
        PartySession session = partyById.get(partyId);
        if (session == null) return;
        for (UUID mId : session.getMembers()) {
            Player p = Bukkit.getPlayer(mId);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    /** True while the party has a dungeon run going or being created. */
    public boolean isLockedByDungeon(UUID partyId) {
        var dungeons = plugin.getDungeonInstanceManager();
        return dungeons != null && dungeons.isPartyBusy(partyId);
    }

    public Optional<PartySession> getParty(UUID partyId) {
        return Optional.ofNullable(partyById.get(partyId));
    }

    public Optional<PartySession> getPartyByPlayer(UUID playerId) {
        UUID pId = playerToParty.get(playerId);
        return pId != null ? getParty(pId) : Optional.empty();
    }

    // Private Methods (DB operations)
    private void savePartyDb(UUID partyId, UUID leaderId) throws SQLException {
        String insertParty = "INSERT INTO parties (party_id, leader_id, state) VALUES (?, ?, 'OPEN') ON CONFLICT DO NOTHING;";
        String insertMember = "INSERT INTO party_members (party_id, player_id, ready) VALUES (?, ?, false) ON CONFLICT DO NOTHING;";
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement s1 = conn.prepareStatement(insertParty)) {
                s1.setObject(1, partyId);
                s1.setObject(2, leaderId);
                s1.executeUpdate();
            }
            try (PreparedStatement s2 = conn.prepareStatement(insertMember)) {
                s2.setObject(1, partyId);
                s2.setObject(2, leaderId);
                s2.executeUpdate();
            }
        }
    }

    private void addMemberDb(UUID partyId, UUID memberId) throws SQLException {
        String insert = "INSERT INTO party_members (party_id, player_id, ready) VALUES (?, ?, false) ON CONFLICT DO NOTHING;";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(insert)) {
            stmt.setObject(1, partyId);
            stmt.setObject(2, memberId);
            stmt.executeUpdate();
        }
    }

    private void removeMemberDb(UUID partyId, UUID memberId) throws SQLException {
        String delete = "DELETE FROM party_members WHERE party_id = ? AND player_id = ?;";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(delete)) {
            stmt.setObject(1, partyId);
            stmt.setObject(2, memberId);
            stmt.executeUpdate();
        }
    }

    private void disbandPartyDb(UUID partyId) throws SQLException {
        // party_members has UNIQUE(player_id): rows must go, or these players can never be
        // recorded in a later party (the insert silently hit ON CONFLICT DO NOTHING).
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM party_members WHERE party_id = ?;")) {
                del.setObject(1, partyId);
                del.executeUpdate();
            }
            try (PreparedStatement upd = conn.prepareStatement("UPDATE parties SET state = 'DISBANDED' WHERE party_id = ?;")) {
                upd.setObject(1, partyId);
                upd.executeUpdate();
            }
        }
    }

    public record PartyInvite(UUID partyId, UUID senderId, long expireTime) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
}
