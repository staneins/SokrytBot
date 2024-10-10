package com.kaminsky.service;

import com.kaminsky.config.BotConfig;
import com.kaminsky.finals.BotFinalVariables;
import com.kaminsky.model.ChatInfo;
import com.kaminsky.model.User;
import com.kaminsky.model.repositories.ChatInfoRepository;
import com.kaminsky.model.repositories.KeyWordRepository;
import com.kaminsky.model.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ChatAdminService chatAdminService;
    private final UserService userService;
    private final SchedulerService schedulerService;
    private final BotConfig botConfig;
    private final ChatInfoRepository chatInfoRepository;
    private final KeyWordRepository keyWordRepository;

    @Autowired
    public AdminService(UserRepository userRepository,
                        MessageService messageService,
                        ChatAdminService chatAdminService,
                        UserService userService,
                        SchedulerService schedulerService,
                        BotConfig botConfig, ChatInfoRepository chatInfoRepository, KeyWordRepository keyWordRepository) {
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.chatAdminService = chatAdminService;
        this.userService = userService;
        this.schedulerService = schedulerService;
        this.botConfig = botConfig;
        this.chatInfoRepository = chatInfoRepository;
        this.keyWordRepository = keyWordRepository;
    }

    public void handleAdminCommandWithReply(Long chatId, Long commandSenderId, String command, Message message) {
        if (!chatAdminService.isAdmin(chatId, commandSenderId)) {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR, message.getMessageId());
            return;
        }
        if (message.getReplyToMessage() == null) {
            messageService.sendMessage(chatId, "Команда должна быть ответом на сообщение", message.getMessageId());
            return;
        }
            Long objectId = message.getReplyToMessage().getFrom().getId();
            String objectName = message.getReplyToMessage().getFrom().getFirstName();
        switch (command) {
            case "/ban@sokrytbot":
                banUser(chatId, commandSenderId, objectId, objectName, message);
                break;
            case "/mute@sokrytbot":
                muteUser(chatId, objectId, objectName, message);
                break;
            case "/unmute@sokrytbot":
                unmuteUser(chatId, objectId, objectName, message);
                break;
            case "/warn@sokrytbot":
                warnUser(chatId, commandSenderId, objectId, objectName, message);
                break;
            case "/check@sokrytbot":
                checkWarns(chatId, objectId, objectName, message);
                break;
            case "/reset@sokrytbot":
                resetWarns(chatId, objectId, objectName, message);
                break;
            default:
                messageService.sendMessage(chatId, BotFinalVariables.UNKNOWN_COMMAND);
                break;
        }
    }

    public void handleConfigCommand(Long chatId, Message message) {
        configCommandReceived(chatId, message.getChat().getId(), botConfig.getBotId());
    }

    public void handleSetWelcomeText(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        Long targetChatId = Long.parseLong(callbackData.split(":")[1]);
        Long chatId = callbackQuery.getMessage().getChatId();

        String helpMessageText = "Пришлите приветственное сообщение. Примеры:\n\n" +
                "Чтобы сделать текст жирным, используйте двойные звездочки (**):\n" +
                "**Этот текст будет жирным**\n\n" +
                "Чтобы сделать текст курсивным, используйте одинарные подчеркивания (_):\n" +
                "_Этот текст будет курсивом_\n\n" +
                "Чтобы сделать текст одновременно жирным и курсивным, используйте сочетание двойных звездочек и одинарных подчеркиваний:\n" +
                "___Этот текст будет и жирным, и курсивом___\n\n" +
                "Для создания гиперссылки используйте формат [] и ():\n" +
                "[Перейти на сайт](https://example.com)\n\n" +
                "Если нужно сделать ссылку на Telegram-профиль пользователя, формат такой:\n" +
                "[Имя пользователя](tg://user?id=" + callbackQuery.getFrom().getId() + ")\n\n" +
                "Для того чтобы зачеркнуть текст, используйте символы ~:\n" +
                "~Этот текст будет зачеркнут~\n\n" +
                "Чтобы подчеркнуть текст, используйте двойные подчеркивания:\n" +
                "__Этот текст будет подчеркнут__";

        messageService.sendMessage(chatId, helpMessageText);
        userService.setAwaitingWelcomeText(true);
        userService.setCurrentChatIdForWelcomeText(targetChatId);
        messageService.executeDeleteMessage(new DeleteMessage(
                String.valueOf(chatId), callbackQuery.getMessage().getMessageId()));
    }

    public void configCommandReceived(Long chatId, Long userId, Long botId) {
        if (chatAdminService.isAdmin(userId)) {
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<Long> menuChatIds = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : chatAdminService.getChatAdministrators().entrySet()) {
                Long key = entry.getKey();
                List<Long> adminList = entry.getValue();
                if (adminList.contains(userId) && (adminList.contains(botId))) {
                    menuChatIds.add(key);
                }
            }
            if (!menuChatIds.isEmpty()) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Какой чат вы хотите настроить?");
                List<InlineKeyboardButton> rowInLine = new ArrayList<>();
                for (Long menuChatId : menuChatIds) {
                    InlineKeyboardButton button = new InlineKeyboardButton();

                    button.setCallbackData(menuChatId.toString());
                    Optional<ChatInfo> chatInfo = chatInfoRepository.findById(menuChatId);
                    if (!chatInfo.isEmpty()) {
                        button.setText(chatInfo.get().getChatTitle());
                    }
                    rowInLine.add(button);
                }

                InlineKeyboardButton keysButton = new InlineKeyboardButton();
                keysButton.setCallbackData(BotFinalVariables.KEYS_BUTTON);
                keysButton.setText("Настроить триггеры");
                rowInLine.add(keysButton);

                InlineKeyboardButton wipeKeysButton = new InlineKeyboardButton();
                wipeKeysButton.setCallbackData(BotFinalVariables.WIPE_KEYS_BUTTON);
                wipeKeysButton.setText("Удалить триггеры");
                rowInLine.add(wipeKeysButton);

                rows.add(rowInLine);
                markup.setKeyboard(rows);
                message.setReplyMarkup(markup);
                messageService.executeMessage(message);
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
            log.info("Пользователь {} неуспешно пытался активировать настройки", userId);
        }
    }

    public void updateCommandReceived(Long chatId, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            chatAdminService.registerAdministrators(chatId);
            messageService.sendMessage(chatId, "Список администраторов обновлен");
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
        }
    }

    public void registerUser(Message message) {
        if (message != null && message.getChat() != null) {
            Long chatId = message.getFrom().getId();

            Optional<User> cachedUser = userService.getUserFromCache(chatId);
            boolean needUpdate = false;

            if (cachedUser.isEmpty()) {
                needUpdate = true;
            } else if (!cachedUser.get().getUserName().equals(message.getFrom().getUserName())) {
                needUpdate = true;
            }

            if (!needUpdate) {
                log.info("Вернули пользователя из кэша {} : {}", cachedUser.get().getFirstName(), cachedUser.get().getChatId());
            }

            if (needUpdate) {
                User user = new User();
                user.setChatId(chatId);
                user.setFirstName(message.getFrom().getFirstName());
                user.setLastName(message.getFrom().getLastName());
                user.setUserName(message.getFrom().getUserName());
                user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));

                userService.updateUserCache(user);
                log.info("Записали нового пользователя: {} : {}", user.getFirstName(), user.getChatId());
            }
        } else {
            log.warn("Попытка зарегистрировать пользователя с пустым сообщением или чатом");
        }
    }

    public User getOrRegisterWarnedUser(Message message, Long warnedUserId) {
        if (message.getReplyToMessage() != null) {
            registerUser(message.getReplyToMessage());
            return userRepository.findById(warnedUserId).orElse(null);
        }
        return null;
    }

    public User getOrRegisterTriggeredUser(Message message, Long warnedUserId) {
        registerUser(message);
        return userRepository.findById(warnedUserId).orElse(null);
    }

    public void handleSetRecurrentText(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        Long targetChatId = Long.parseLong(callbackData.split(":")[1]);
        Long chatId = callbackQuery.getMessage().getChatId();

        String helpMessageText = "Пришлите повторяющееся сообщение. Примеры:\n\n" +
                "Чтобы сделать текст жирным, используйте двойные звездочки (**):\n" +
                "**Этот текст будет жирным**\n\n" +
                "Чтобы сделать текст курсивным, используйте одинарные подчеркивания (_):\n" +
                "_Этот текст будет курсивом_\n\n" +
                "Чтобы сделать текст одновременно жирным и курсивным, используйте сочетание двойных звездочек и одинарных подчеркиваний:\n" +
                "___Этот текст будет и жирным, и курсивом___\n\n" +
                "Для создания гиперссылки используйте формат [] и ():\n" +
                "[Перейти на сайт](https://example.com)\n\n" +
                "Если нужно сделать ссылку на Telegram-профиль пользователя, формат такой:\n" +
                "[Имя пользователя](tg://user?id=" + callbackQuery.getFrom().getId() + ")\n\n" +
                "Для того чтобы зачеркнуть текст, используйте символы ~:\n" +
                "~Этот текст будет зачеркнут~\n\n" +
                "Чтобы подчеркнуть текст, используйте двойные подчеркивания:\n" +
                "__Этот текст будет подчеркнут__";

        messageService.sendMarkdownMessage(chatId, messageService.fixMarkdownText(helpMessageText));
        userService.setAwaitingRecurrentText(true);
        userService.setCurrentChatIdForRecurrentText(targetChatId);
        messageService.executeDeleteMessage(new DeleteMessage(
                String.valueOf(chatId), callbackQuery.getMessage().getMessageId()));
    }

    public void handleConfigCallbackQuery(CallbackQuery callbackQuery) {
        Long targetChatId = Long.parseLong(callbackQuery.getData());
        messageService.sendConfigOptions(callbackQuery.getMessage().getChatId(), targetChatId, callbackQuery.getMessage().getMessageId());
    }

    public void banUser(Long chatId, Long commandSenderId, Long bannedUserId, String bannedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, commandSenderId)) {
            if (!isUserAlreadyBanned(bannedUserId, chatId)) {
                if (chatAdminService.isAdmin(chatId, bannedUserId)) {
                    messageService.sendMessage(chatId, "Не могу забанить администратора.");
                } else {
                    BanChatMember banChatMember = new BanChatMember();
                    banChatMember.setChatId(String.valueOf(chatId));
                    banChatMember.setUserId(bannedUserId);
                    messageService.executeBanChatMember(banChatMember);

                    String text = "<a href=\"tg://user?id=" + bannedUserId + "\">" + bannedUserNickname + "</a> уничтожен";
                    messageService.sendHTMLMessage(chatId, text, message.getMessageId());

                    userService.addBannedUser(bannedUserId);
                    schedulerService.startBannedUsersCleanupTask(1, TimeUnit.MINUTES);

                }
            } else {
                messageService.sendMessage(chatId, BotFinalVariables.USER_BANNED);
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
        }
    }

    public void warnUser(Long chatId, Long commandSenderId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, commandSenderId)) {
            if (!isUserAlreadyBanned(warnedUserId, chatId)) {
                User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
                if (warnedUser != null) {
                    Byte warnsCount = warnedUser.getNumberOfWarns();
                    if (warnsCount == null) {
                        warnedUser.setNumberOfWarns((byte) 1);
                        userRepository.save(warnedUser);

                        String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> предупрежден. \n" +
                                "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                        messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                        log.info("Пользователь {} предупрежден. Количество предупреждений: 1 из 3", warnedUserNickname);
                    } else if (warnsCount == 2) {
                        warnedUser.setNumberOfWarns((byte) 0);
                        userRepository.save(warnedUser);

                        banUser(chatId, commandSenderId, warnedUserId, warnedUserNickname, message);
                        log.info("Пользователь {} получил 3-е предупреждение и был забанен.", warnedUserNickname);
                    } else {
                        warnedUser.setNumberOfWarns((byte) (warnedUser.getNumberOfWarns() + 1));
                        userRepository.save(warnedUser);

                        String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> предупрежден. \n" +
                                "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                        messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                        log.info("Пользователь {} предупрежден. Количество предупреждений: {} из 3", warnedUserNickname, warnedUser.getNumberOfWarns());
                    }
                } else {
                    messageService.sendMessage(chatId, BotFinalVariables.ERROR);
                }
            } else {
                messageService.sendMessage(chatId, BotFinalVariables.USER_BANNED);
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
        }
    }

    public void checkWarns(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                Byte warnsCount = warnedUser.getNumberOfWarns();
                if (warnsCount == null) {
                    warnedUser.setNumberOfWarns((byte) 0);
                    userRepository.save(warnedUser);
                }
                String text = "Пользователь <a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> " +
                        "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";
                messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                log.info("Проверка предупреждений для пользователя {}: {} из 3", warnedUserNickname, warnedUser.getNumberOfWarns());
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
        }
    }

    public void resetWarns(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                warnedUser.setNumberOfWarns((byte) 0);
                userRepository.save(warnedUser);

                String text = "Предупреждения сброшены\n" +
                        "Пользователь <a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a>\n" +
                        "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";
                messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                log.info("Предупреждения пользователя {} сброшены.", warnedUserNickname);
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
        }
    }

    public void wipeAllKeys(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        keyWordRepository.deleteAll();
        messageService.sendMessage(chatId, "Все слова-триггеры успешно удалены");
        messageService.executeDeleteMessage(new DeleteMessage(
                String.valueOf(chatId), callbackQuery.getMessage().getMessageId()));
    }

    public void handleKeyWordsCallbackQuery(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        messageService.sendMessage(chatId, "Пришлите новые слова-триггеры одним сообщением через запятую");
        userService.setAwaitingKeyWords(true);
        messageService.executeDeleteMessage(new DeleteMessage(
                String.valueOf(chatId), callbackQuery.getMessage().getMessageId()));
    }

    public void handleUnmuteCommandCallbackQuery(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        Long chatId = Long.parseLong(callbackData.split(":")[1]);
        Long userId = Long.parseLong(callbackData.split(":")[2]);
        String userNickname = callbackData.split(":")[3];
        Long userPressingTheButtonId = callbackQuery.getFrom().getId();
        if (chatAdminService.isAdmin(chatId, userPressingTheButtonId)) {
            unmuteUser(chatId, userId, userNickname, callbackQuery.getMessage(), true);
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
            log.info("Пользователь {} попытался размьютить пользователя {} не обладая правами администратора", userPressingTheButtonId, userId);
        }
    }

    public void muteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            if (!isUserAlreadyBanned(warnedUserId, chatId)) {
                User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
                if (warnedUser != null) {
                    Duration muteDuration = Duration.ofDays(1);
                    RestrictChatMember restrictChatMember = new RestrictChatMember();
                    restrictChatMember.setChatId(chatId.toString());
                    restrictChatMember.setUserId(warnedUserId);
                    restrictChatMember.setPermissions(new ChatPermissions());
                    restrictChatMember.forTimePeriodDuration(muteDuration);
                    messageService.executeRestrictChatMember(restrictChatMember);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> обеззвучен на сутки";

                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    List<InlineKeyboardButton> rowInLine = new ArrayList<>();

                    InlineKeyboardButton unmuteButton = new InlineKeyboardButton();
                    unmuteButton.setCallbackData(BotFinalVariables.UNMUTE_BUTTON + ":" + chatId + ":" + warnedUserId + ":" + warnedUserNickname);
                    unmuteButton.setText("Снять ограничения");

                    rowInLine.add(unmuteButton);
                    rows.add(rowInLine);
                    markup.setKeyboard(rows);

                    messageService.sendHTMLMessageWithKeyboard(chatId, text, markup, message.getMessageId());
                    log.info("Пользователь {} обеззвучен на сутки.", warnedUserNickname);
                }
            } else {
                messageService.sendMessage(chatId, BotFinalVariables.USER_BANNED);
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR, message.getMessageId());
        }
    }

    public void muteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message, boolean isAdmin) {
        if (isAdmin) {
            User warnedUser = getOrRegisterTriggeredUser(message, warnedUserId);
            if (warnedUser != null) {
                if (!chatAdminService.isAdmin(chatId, warnedUserId)) {
                Duration muteDuration = Duration.ofDays(1);
                RestrictChatMember restrictChatMember = new RestrictChatMember();
                restrictChatMember.setChatId(chatId.toString());
                restrictChatMember.setUserId(warnedUserId);
                restrictChatMember.setPermissions(new ChatPermissions());
                restrictChatMember.forTimePeriodDuration(muteDuration);
                messageService.executeRestrictChatMember(restrictChatMember);

                String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> обеззвучен на сутки";

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> rowInLine = new ArrayList<>();

                InlineKeyboardButton unmuteButton = new InlineKeyboardButton();
                unmuteButton.setCallbackData(BotFinalVariables.UNMUTE_BUTTON + ":" + chatId + ":" + warnedUserId + ":" + warnedUserNickname);
                unmuteButton.setText("Снять ограничения");

                rowInLine.add(unmuteButton);
                rows.add(rowInLine);
                markup.setKeyboard(rows);

                messageService.sendHTMLMessageWithKeyboard(chatId, text, markup, message.getMessageId());
                log.info("Пользователь {} обеззвучен на сутки.", warnedUserNickname);
            } else {
                    messageService.sendMessage(chatId, BotFinalVariables.IS_ADMIN_ERROR);
        }
        }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR);
        }
    }

    public void unmuteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            if (!isUserAlreadyBanned(warnedUserId, chatId)) {
                ChatPermissions permissions = new ChatPermissions();
                permissions.setCanSendMessages(true);
                permissions.setCanSendMediaMessages(true);
                permissions.setCanSendPolls(true);
                permissions.setCanSendOtherMessages(true);
                permissions.setCanAddWebPagePreviews(true);
                permissions.setCanChangeInfo(true);
                permissions.setCanInviteUsers(true);

                RestrictChatMember restrictChatMember = new RestrictChatMember();
                restrictChatMember.setChatId(chatId);
                restrictChatMember.setUserId(warnedUserId);
                restrictChatMember.setPermissions(permissions);

                messageService.executeRestrictChatMember(restrictChatMember);
                messageService.sendHTMLMessage(chatId, "Все ограничения сняты с пользователя " + "<a href=\"tg://user?id=" +
                        warnedUserId + "\">" + warnedUserNickname + "</a>");

                messageService.executeDeleteMessage(new DeleteMessage(
                        String.valueOf(chatId), message.getMessageId()));
            } else {
                messageService.sendMessage(chatId, BotFinalVariables.USER_BANNED);
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR, message.getMessageId());
        }
    }

    public void unmuteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message, Boolean isAdmin) {
        if (isAdmin) {
            ChatPermissions permissions = new ChatPermissions();
            permissions.setCanSendMessages(true);
            permissions.setCanSendMediaMessages(true);
            permissions.setCanSendPolls(true);
            permissions.setCanSendOtherMessages(true);
            permissions.setCanAddWebPagePreviews(true);
            permissions.setCanChangeInfo(true);
            permissions.setCanInviteUsers(true);

            RestrictChatMember restrictChatMember = new RestrictChatMember();
            restrictChatMember.setChatId(chatId);
            restrictChatMember.setUserId(warnedUserId);
            restrictChatMember.setPermissions(permissions);

            messageService.executeRestrictChatMember(restrictChatMember);
            messageService.sendHTMLMessage(chatId, "Все ограничения сняты с пользователя " + "<a href=\"tg://user?id=" +
                    warnedUserId + "\">" + warnedUserNickname + "</a>");

            messageService.executeDeleteMessage(new DeleteMessage(
                    String.valueOf(chatId), message.getMessageId()));
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR, message.getMessageId());
        }
    }

    public void wipeAllMessages(Long chatId, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            List<Message> allMessages = messageService.getAllMessages(chatId)
                    .getOrDefault(chatId, Collections.emptyList());

            if (!allMessages.isEmpty()) {
                for (Message chatMessage : allMessages) {
                    try {
                        messageService.executeDeleteMessage(new DeleteMessage(
                                String.valueOf(chatId), chatMessage.getMessageId()));
                    } catch (Exception e) {
                        System.err.println("Не удалось удалить сообщение: " + chatMessage.getMessageId());
                    }
                }
                messageService.clearAllMessages(chatId);
                messageService.sendMessage(chatId, "Все сообщения успешно удалены");
            } else {
                messageService.sendMessage(chatId, "Нет сообщений для удаления");
            }
        } else {
            messageService.sendMessage(chatId, BotFinalVariables.NOT_AN_ADMIN_ERROR, message.getMessageId());
        }
    }

    private boolean isUserAlreadyBanned(Long userId, Long chatId) {
        GetChatMember getChatMember = new GetChatMember();
        getChatMember.setChatId(chatId.toString());
        getChatMember.setUserId(userId);
        ChatMember chatMember = messageService.executeGetChatMember(getChatMember);
        return chatMember instanceof ChatMemberBanned;
    }

}