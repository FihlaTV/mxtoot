/*
 * Copyright sablintolya@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.ma1uta.mxtoot.mastodon;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import com.sys1yagi.mastodon4j.MastodonClient;
import com.sys1yagi.mastodon4j.api.Handler;
import com.sys1yagi.mastodon4j.api.Shutdownable;
import com.sys1yagi.mastodon4j.api.entity.Account;
import com.sys1yagi.mastodon4j.api.entity.Application;
import com.sys1yagi.mastodon4j.api.entity.Attachment;
import com.sys1yagi.mastodon4j.api.entity.Emoji;
import com.sys1yagi.mastodon4j.api.entity.Mention;
import com.sys1yagi.mastodon4j.api.entity.Notification;
import com.sys1yagi.mastodon4j.api.entity.Status;
import com.sys1yagi.mastodon4j.api.entity.Tag;
import com.sys1yagi.mastodon4j.api.exception.Mastodon4jRequestException;
import com.sys1yagi.mastodon4j.api.method.Accounts;
import com.sys1yagi.mastodon4j.api.method.Statuses;
import io.github.ma1uta.matrix.bot.Context;
import io.github.ma1uta.matrix.client.MatrixClient;
import io.github.ma1uta.mxtoot.matrix.MxTootConfig;
import io.github.ma1uta.mxtoot.matrix.MxTootDao;
import io.github.ma1uta.mxtoot.matrix.MxTootPersistentService;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Mastodon client.
 */
public class MxMastodonClient implements Handler, Supplier<Void> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MxMastodonClient.class);

    private final MastodonClient mastodonClient;
    private final Context<MxTootConfig, MxTootDao, MxTootPersistentService<MxTootDao>, MxMastodonClient> holder;
    private Shutdownable shutdownable;
    private boolean running = false;
    private DateTimeFormatter dateTimeFormatter;
    private Template postTemplate;
    private Template replyTemplate;
    private Template boostTemplate;
    private Template mentionTemplate;
    private Template favouriteTemplate;
    private Template followTemplate;

    public MxMastodonClient(MastodonClient mastodonClient,
                            Context<MxTootConfig, MxTootDao, MxTootPersistentService<MxTootDao>, MxMastodonClient> holder) {
        this.mastodonClient = mastodonClient;
        this.holder = holder;
    }

    public MastodonClient getMastodonClient() {
        return mastodonClient;
    }

    public boolean isRunning() {
        return running;
    }

    public DateTimeFormatter getDateTimeFormatter() {
        return dateTimeFormatter;
    }

    public void setDateTimeFormatter(DateTimeFormatter dateTimeFormatter) {
        this.dateTimeFormatter = dateTimeFormatter;
    }

    public Template getPostTemplate() {
        return postTemplate;
    }

    public void setPostTemplate(Template postTemplate) {
        this.postTemplate = postTemplate;
    }

    public Template getBoostTemplate() {
        return boostTemplate;
    }

    public void setBoostTemplate(Template boostTemplate) {
        this.boostTemplate = boostTemplate;
    }

    public Template getReplyTemplate() {
        return replyTemplate;
    }

    public void setReplyTemplate(Template replyTemplate) {
        this.replyTemplate = replyTemplate;
    }

    public Template getMentionTemplate() {
        return mentionTemplate;
    }

    public void setMentionTemplate(Template mentionTemplate) {
        this.mentionTemplate = mentionTemplate;
    }

    public Template getFavouriteTemplate() {
        return favouriteTemplate;
    }

    public void setFavouriteTemplate(Template favouriteTemplate) {
        this.favouriteTemplate = favouriteTemplate;
    }

    public Template getFollowTemplate() {
        return followTemplate;
    }

    public void setFollowTemplate(Template followTemplate) {
        this.followTemplate = followTemplate;
    }

    public Context<MxTootConfig, MxTootDao, MxTootPersistentService<MxTootDao>, MxMastodonClient> getHolder() {
        return holder;
    }

    /**
     * Start streaming.
     *
     * @return {@code true} if has started streaming, else {@code false}.
     */
    public boolean streaming() {
        if (isRunning()) {
            return true;
        }

        try {
            if (this.shutdownable != null) {
                this.shutdownable.shutdown();
            }
            this.shutdownable = new Streaming(getMastodonClient(), true,
                response -> {
                    MatrixClient matrixClient = getHolder().getMatrixClient();
                    matrixClient.room().joinedRooms().join()
                        .forEach(roomId -> matrixClient.event().sendNotice(roomId, "Failed start streaming: " + response.message()));
                }).user(this);
            this.running = true;
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("Failed streaming", e);
            return false;
        }
    }

    /**
     * Stop streaming.
     *
     * @return nothing.
     */
    public Void get() {
        if (this.shutdownable != null) {
            this.shutdownable.shutdown();
        }
        this.running = false;
        return null;
    }

    @Override
    public void onDelete(long l) {

    }

    @Override
    public void onStatus(Status status) {
        writeMessage(writeStatus(status));
    }

    @Override
    public void onNotification(Notification notification) {
        Template template;
        switch (notification.getType()) {
            case "mention":
                template = mention();
                break;
            case "reblog":
                template = reblog();
                break;
            case "favourite":
                template = favourite();
                break;
            case "follow":
                template = follow();
                break;
            default:
                writeMessage(String.format("Unknown notification: %s at [%s]: %d", notification.getType(), notification.getCreatedAt(),
                    notification.getId()));
                return;
        }

        Map<String, Object> notificationMap = new HashMap<>();
        notificationMap.put("id", notification.getId());
        notificationMap.put("created_at", notification.getCreatedAt());
        notificationMap.put("account", accountToMap(notification.getAccount()));
        if (notification.getStatus() != null) {
            notificationMap.put("status", statusToMap(notification.getStatus(), true));
        }
        notificationMap.put("type", notification.getType());

        String message = formatTemplate(template, notificationMap);

        writeMessage(message);
    }

    protected void writeMessage(String message) {
        getHolder().runInTransaction((holder, dao) -> {
            MatrixClient matrixClient = holder.getMatrixClient();
            try {
                matrixClient.room().joinedRooms().join()
                    .forEach(roomId -> matrixClient.event().sendFormattedNotice(roomId, Jsoup.parse(message).text(), message));
            } catch (RuntimeException e) {
                LOGGER.error("Failed write a message", e);
            }
        });
    }

    private Template mention() {
        MxTootConfig config = getHolder().getConfig();
        MxMastodonClient mastodonClient = getHolder().getData();
        if (mastodonClient.getMentionTemplate() == null) {
            mastodonClient.setMentionTemplate(compileTemplate(config.getMentionFormat()));
        }
        return mastodonClient.getMentionTemplate();
    }

    private Template reblog() {
        MxTootConfig config = getHolder().getConfig();
        MxMastodonClient mastodonClient = getHolder().getData();
        if (mastodonClient.getBoostTemplate() == null) {
            mastodonClient.setBoostTemplate(compileTemplate(config.getBoostFormat()));
        }
        return mastodonClient.getBoostTemplate();
    }

    private Template favourite() {
        MxTootConfig config = getHolder().getConfig();
        MxMastodonClient mastodonClient = getHolder().getData();
        if (mastodonClient.getFavouriteTemplate() == null) {
            mastodonClient.setFavouriteTemplate(compileTemplate(config.getFavouriteFormat()));
        }
        return mastodonClient.getFavouriteTemplate();
    }

    private Template follow() {
        MxTootConfig config = getHolder().getConfig();
        MxMastodonClient mastodonClient = getHolder().getData();
        if (mastodonClient.getFollowTemplate() == null) {
            mastodonClient.setFollowTemplate(compileTemplate(config.getFollowFormat()));
        }
        return mastodonClient.getFollowTemplate();
    }

    /**
     * Retrieve formatted message of the status.
     *
     * @param status status.
     * @return formatted message of the status.
     */
    public String writeStatus(Status status) {
        MxTootConfig config = getHolder().getConfig();
        MxMastodonClient mastodonClient = getHolder().getData();
        Template template;
        if (status.getReblog() != null) {
            if (mastodonClient.getBoostTemplate() == null) {
                mastodonClient.setBoostTemplate(compileTemplate(config.getBoostFormat()));
            }
            template = mastodonClient.getBoostTemplate();
        } else if (status.getInReplyToId() != null) {
            if (mastodonClient.getReplyTemplate() == null) {
                mastodonClient.setReplyTemplate(compileTemplate(config.getReplyFormat()));
            }
            template = mastodonClient.getReplyTemplate();
        } else {
            if (mastodonClient.getPostTemplate() == null) {
                mastodonClient.setPostTemplate(compileTemplate(config.getPostFormat()));
            }
            template = mastodonClient.getPostTemplate();
        }

        Map<String, Object> statusMap = mastodonClient.statusToMap(status, true);
        if (config.getFetchMissingStatuses() != null && config.getFetchMissingStatuses()) {
            if (status.getInReplyToId() != null) {
                try {
                    Status reply = new Statuses(mastodonClient.getMastodonClient()).getStatus(status.getInReplyToId()).execute();
                    statusMap.put("in_reply_to", mastodonClient.statusToMap(reply, false));
                } catch (Mastodon4jRequestException e) {
                    LOGGER.error("Cannot fetch status: " + status.getInReplyToId(), e);
                }
            }

            if (status.getInReplyToAccountId() != null) {
                try {
                    Account replyAccount = new Accounts(mastodonClient.getMastodonClient()).getAccount(status.getInReplyToAccountId())
                        .execute();
                    statusMap.put("in_reply_to_account", mastodonClient.accountToMap(replyAccount));
                } catch (Mastodon4jRequestException e) {
                    LOGGER.error("Cannot fetch account: " + status.getInReplyToAccountId());
                }
            }
        }

        return formatTemplate(template, statusMap);
    }

    protected String formatTemplate(Template template, Map<String, Object> statusMap) {
        try {
            return template.execute(statusMap);
        } catch (MustacheException e) {
            String msg = "Cannot create a post";
            LOGGER.error(msg, e);
            return msg;
        }
    }

    /**
     * Compile template.
     *
     * @param template template.
     * @return compiled template.
     */
    public Template compileTemplate(String template) {
        return Mustache.compiler().defaultValue("").escapeHTML(false).compile(template);
    }

    protected Map<String, Object> statusToMap(Status status, boolean parseReblog) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", status.getId());
        map.put("uri", status.getUri());
        map.put("url", status.getUrl());
        map.put("account", accountToMap(status.getAccount()));
        map.put("in_reply_to_id", status.getInReplyToId());
        map.put("in_reply_to_acoount_id", status.getInReplyToAccountId());
        if (status.getReblog() != null && parseReblog) {
            map.put("reblog", statusToMap(status.getReblog(), false));
        }
        map.put("content", status.getContent());
        if (getDateTimeFormatter() == null) {
            MxTootConfig config = getHolder().getConfig();
            setDateTimeFormatter(DateTimeFormatter.ofPattern(config.getDateTimeFormat(), new Locale(config.getDateTimeLocale())));
        }
        LocalDateTime createdAt = LocalDateTime.parse(status.getCreatedAt(), DateTimeFormatter.ISO_DATE_TIME);
        map.put("created_at", createdAt.format(getDateTimeFormatter()));
        map.put("emojis", status.getEmojis().stream().filter(Objects::nonNull).map(this::emojiToMap).collect(Collectors.toList()));
        map.put("reblogs_count", status.getReblogsCount());
        map.put("favourites_count", status.getFavouritesCount());
        map.put("reblogged", status.isReblogged());
        map.put("favourited", status.isFavourited());
        map.put("sensitive", status.isSensitive());
        map.put("spoiler_text", status.getSpoilerText());
        map.put("visibility", status.getVisibility());
        map.put("media_attachments",
            status.getMediaAttachments().stream().filter(Objects::nonNull).map(this::attachmentToMap).collect(Collectors.toList()));
        map.put("mentions", status.getMentions().stream().filter(Objects::nonNull).map(this::mentionToMap).collect(Collectors.toList()));
        map.put("tags", status.getTags().stream().filter(Objects::nonNull).map(this::tagToMap).collect(Collectors.toList()));
        map.put("application", applicationToMap(status.getApplication()));
        return map;
    }

    protected Map<String, Object> accountToMap(Account account) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", account.getId());
        map.put("username", account.getUserName());
        map.put("acct", account.getAcct());
        map.put("display_name", account.getDisplayName());
        map.put("locked", account.isLocked());
        map.put("created_at", account.getCreatedAt());
        map.put("followers_count", account.getFollowersCount());
        map.put("following_acount", account.getFollowingCount());
        map.put("statuses_count", account.getStatusesCount());
        map.put("note", account.getNote());
        map.put("url", account.getUrl());
        map.put("avatar", account.getAvatar());
        map.put("header", account.getHeader());
        return map;
    }

    protected Map<String, String> emojiToMap(Emoji emoji) {
        Map<String, String> map = new HashMap<>();
        map.put("shortcode", emoji.getShortcode());
        map.put("static_url", emoji.getStaticUrl());
        map.put("url", emoji.getUrl());
        return map;
    }

    protected Map<String, Object> attachmentToMap(Attachment attachment) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", attachment.getId());
        map.put("type", attachment.getType());
        map.put("url", attachment.getUrl());
        map.put("remote_url", attachment.getRemoteUrl());
        map.put("preview_url", attachment.getPreviewUrl());
        map.put("text_url", attachment.getTextUrl());
        return map;
    }

    protected Map<String, Object> mentionToMap(Mention mention) {
        Map<String, Object> map = new HashMap<>();
        map.put("url", mention.getUrl());
        map.put("username", mention.getUsername());
        map.put("acct", mention.getAcct());
        map.put("id", mention.getId());
        return map;
    }

    protected Map<String, Object> tagToMap(Tag tag) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", tag.getName());
        map.put("url", tag.getUrl());
        return map;
    }

    protected Map<String, Object> applicationToMap(Application application) {
        Map<String, Object> map = new HashMap<>();
        if (application != null) {
            map.put("name", application.getName());
            map.put("website", application.getWebsite());
        }
        return map;
    }
}
