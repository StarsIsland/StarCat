/*
 * Copyright 2021 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.downstream;

import com.nimbusds.jwt.SignedJWT;
import com.nukkitx.protocol.bedrock.BedrockClient;
import com.nukkitx.protocol.bedrock.BedrockClientSession;
import com.nukkitx.protocol.bedrock.packet.*;
import com.nukkitx.protocol.bedrock.util.EncryptionUtils;
import dev.waterdog.waterdogpe.network.ServerInfo;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.rewrite.types.BlockPalette;
import dev.waterdog.waterdogpe.network.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.network.session.ServerConnection;
import dev.waterdog.waterdogpe.network.session.SessionInjections;
import dev.waterdog.waterdogpe.network.session.TransferCallback;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.exceptions.CancelSignalException;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import javax.crypto.SecretKey;
import java.net.URI;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;

import static dev.waterdog.waterdogpe.player.PlayerRewriteUtils.*;

public class SwitchDownstreamHandler extends AbstractDownstreamHandler {

    private final BedrockClient client;
    private final ServerInfo serverInfo;

    public SwitchDownstreamHandler(ProxiedPlayer player, ServerInfo serverInfo, BedrockClient client) {
        super(player);
        this.serverInfo = serverInfo;
        this.client = client;
    }

    public BedrockClientSession getDownstream() {
        return this.client.getSession();
    }

    @Override
    public final boolean handle(ServerToClientHandshakePacket packet) {
        try {
            SignedJWT saltJwt = SignedJWT.parse(packet.getJwt());
            URI x5u = saltJwt.getHeader().getX509CertURL();
            ECPublicKey serverKey = EncryptionUtils.generateKey(x5u.toASCIIString());
            SecretKey key = EncryptionUtils.getSecretKey(
                    this.player.getLoginData().getKeyPair().getPrivate(),
                    serverKey,
                    Base64.getDecoder().decode(saltJwt.getJWTClaimsSet().getStringClaim("salt"))
            );
            this.getDownstream().enableEncryption(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ClientToServerHandshakePacket clientToServerHandshake = new ClientToServerHandshakePacket();
        this.getDownstream().sendPacketImmediately(clientToServerHandshake);
        throw CancelSignalException.CANCEL;
    }

    @Override
    public final boolean handle(ResourcePacksInfoPacket packet) {
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        this.getDownstream().sendPacketImmediately(response);
        throw CancelSignalException.CANCEL;
    }

    @Override
    public final boolean handle(ResourcePackStackPacket packet) {
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        this.getDownstream().sendPacketImmediately(response);
        throw CancelSignalException.CANCEL;
    }

    @Override
    public boolean handle(PlayStatusPacket packet) {
        return this.onPlayStatus(packet, message -> {
            this.client.close();
            this.player.setPendingConnection(null);
            this.player.sendMessage(new TranslationContainer("waterdog.downstream.transfer.failed", this.serverInfo.getServerName(), message));
        }, this.getDownstream());
    }

    @Override
    public final boolean handle(StartGamePacket packet) {
        RewriteData rewriteData = this.player.getRewriteData();
        rewriteData.setOriginalEntityId(packet.getRuntimeEntityId());
        rewriteData.setGameRules(packet.getGamerules());
        rewriteData.setSpawnPosition(packet.getPlayerPosition());
        rewriteData.setRotation(packet.getRotation());

        if (this.player.getProtocol().isBeforeOrEqual(ProtocolVersion.MINECRAFT_PE_1_16_20)) {
            BlockPalette palette = BlockPalette.getPalette(packet.getBlockPalette(), this.player.getProtocol());
            rewriteData.setBlockPaletteRewrite(palette.createRewrite(rewriteData.getBlockPalette()));
        } else {
            rewriteData.setBlockProperties(packet.getBlockProperties());
        }

        ServerConnection oldServer = this.player.getServer();
        oldServer.getInfo().removePlayer(this.player);
        oldServer.disconnect();

        Collection<UUID> playerList = this.player.getPlayers();
        injectRemoveAllPlayers(this.player.getUpstream(), playerList);
        playerList.clear();

        Long2LongMap entityLinks = this.player.getEntityLinks();
        for (Long2LongMap.Entry entry : entityLinks.long2LongEntrySet()) {
            injectRemoveEntityLink(this.player.getUpstream(), entry.getLongKey(), entry.getLongValue());
        }
        entityLinks.clear();

        LongSet entities = this.player.getEntities();
        for (long entityId : entities) {
            injectRemoveEntity(this.player.getUpstream(), entityId);
        }
        entities.clear();

        ObjectSet<String> scoreboards = this.player.getScoreboards();
        for (String scoreboard : scoreboards) {
            injectRemoveObjective(this.player.getUpstream(), scoreboard);
        }
        scoreboards.clear();

        LongSet bossbars = this.player.getBossbars();
        for (long bossbarId : bossbars) {
            injectRemoveBossbar(this.player.getUpstream(), bossbarId);
        }
        bossbars.clear();

        injectGameMode(this.player.getUpstream(), packet.getPlayerGameType());
        injectSetDifficulty(this.player.getUpstream(), packet.getDifficulty());
        injectPosition(this.player.getUpstream(), rewriteData.getSpawnPosition(), rewriteData.getRotation(), rewriteData.getEntityId());
        this.getDownstream().sendPacket(this.player.getLoginData().getChunkRadius());

        /*
         * Client does not accept ChangeDimensionPacket when dimension is same as current dimension.
         * Therefore we are attempting to do dimension change sequence which uses 2 dim changes.
         * After client successfully changes dimension we receive PlayerActionPacket#DIMENSION_CHANGE_SUCCESS and send second dim change.
         */
        rewriteData.setDimension(determineDimensionId(packet.getDimensionId()));
        rewriteData.setTransferCallback(new TransferCallback(this.player, this.client, this.serverInfo));

        injectDimensionChange(this.player.getUpstream(), rewriteData.getDimension(), packet.getPlayerPosition());
        this.player.setDimensionChangeState(TransferCallback.TRANSFER_PHASE_1); // Except first dim change packet.
        SessionInjections.injectPreDownstreamHandlers(this.getDownstream(), this.player);
        throw CancelSignalException.CANCEL;
    }

    @Override
    public boolean handle(DisconnectPacket packet) {
        TransferCallback transferCallback = this.player.getRewriteData().getTransferCallback();
        if (transferCallback != null) {
            // Player was already disconnected from old downstream
            transferCallback.onTransferFailed();
            return false;
        }

        this.client.close();
        this.player.setPendingConnection(null);
        this.player.sendMessage(new TranslationContainer("waterdog.downstream.transfer.failed", this.serverInfo.getServerName(), packet.getKickMessage()));
        return false;
    }
}
