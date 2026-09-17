package com.dimensionalminer.core.dimensionalMinerCore.party;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartySession {

    // Variables/Fields
    private final UUID partyId;
    private UUID leaderId;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
    private final Instant createdAt;

    // Constructors
    public PartySession(UUID partyId, UUID leaderId) {
        this.partyId = partyId;
        this.leaderId = leaderId;
        this.members.add(leaderId);
        this.createdAt = Instant.now();
    }

    // Public Methods
    public UUID getPartyId() { return partyId; }
    public UUID getLeaderId() { return leaderId; }
    public void setLeaderId(UUID leaderId) { this.leaderId = leaderId; }
    public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }
    public void addMember(UUID memberId) { members.add(memberId); }
    public void removeMember(UUID memberId) { members.remove(memberId); }
    public Instant getCreatedAt() { return createdAt; }
}
