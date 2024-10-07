package com.kaminsky.service;

import com.kaminsky.config.BotConfig;
import com.kaminsky.model.User;
import com.kaminsky.model.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AdminService {

    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ChatAdminService chatAdminService;
    private final TelegramBot telegramBot;
    private final UserService userService;
    private final SchedulerService schedulerService;
    private final BotConfig botConfig;

    @Autowired
    public AdminService(UserRepository userRepository,
                        MessageService messageService,
                        ChatAdminService chatAdminService,
                        TelegramBot telegramBot,
                        UserService userService, SchedulerService schedulerService, BotConfig botConfig) {
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.chatAdminService = chatAdminService;
        this.telegramBot = telegramBot;
        this.userService = userService;
        this.schedulerService = schedulerService;
        this.botConfig = botConfig;
    }

    public void handleAdminCommand(Long chatId, Long commandSenderId, String command, Message message) {
        if (!chatAdminService.isAdmin(chatId, commandSenderId)) {
            messageService.sendMessage(chatId, "Для этого нужны права администратора.");
            return;
        }

        Long objectId = message.getReplyToMessage().getFrom().getId();
        String objectName = message.getReplyToMessage().getFrom().getFirstName();

        switch (command) {
            case "/ban":
                banUser(chatId, commandSenderId, objectId, objectName, message);
                break;
            case "/mute":
                muteUser(chatId, objectId, objectName, message);
                break;
            case "/warn":
                warnUser(chatId, commandSenderId, objectId, objectName, message);
                break;
            case "/check":
                checkWarns(chatId, objectId, objectName, message);
                break;
            case "/reset":
                resetWarns(chatId, objectId, objectName, message);
                break;
            case "/wipe":
                wipeAllMessages(chatId);
                break;
            default:
                messageService.sendMessage(chatId, "Неизвестная административная команда.");
                break;
        }
    }

    public void handleConfigCommand(Long chatId, Message message) {
        configCommandReceived(chatId, message.getChat().getId(), telegramBot.getBotId());
    }

    public void handleSetWelcomeText(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        Long targetChatId = Long.parseLong(callbackData.split(":")[1]);

        String helpMessageText = "Пришлите приветственное сообщение. Примеры:\n" +
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

        messageService.sendMessage(targetChatId, helpMessageText);
        userService.setAwaitingWelcomeText(true);
    }

    public void configCommandReceived(Long chatId, Long userId, Long botId) {
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
        if (menuChatIds.size() > 0) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Какой чат вы хотите настроить?");

            for (Long menuChatId : menuChatIds) {
                List<InlineKeyboardButton> rowInLine = new ArrayList<>();
                InlineKeyboardButton button = new InlineKeyboardButton();

                button.setCallbackData(menuChatId.toString());
                button.setText(messageService.getChatTitle(menuChatId));

                rowInLine.add(button);
                rows.add(rowInLine);
                markup.setKeyboard(rows);
                message.setReplyMarkup(markup);
            }
            messageService.sendMessage(message);
        }
    }

    public void handleSetRecurrentText(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        Long targetChatId = Long.parseLong(callbackData.split(":")[1]);

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

        messageService.sendMessage(targetChatId, helpMessageText);
        userService.setAwaitingRecurrentText(true);
    }

    public void handleConfigCallbackQuery(CallbackQuery callbackQuery) {
        log.info("Получен callbackQuery с данными: " + callbackQuery.getData());
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String callbackData = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Что вы хотите настроить?");
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();
        InlineKeyboardButton welcomeTextButton = new InlineKeyboardButton();
        InlineKeyboardButton recurrentTextButton = new InlineKeyboardButton();
        welcomeTextButton.setCallbackData("WELCOME_TEXT_BUTTON" + ":" + callbackData);
        welcomeTextButton.setText("Приветственное сообщение");
        recurrentTextButton.setText("Повторяющееся сообщение");
        recurrentTextButton.setCallbackData("RECURRENT_TEXT_BUTTON" + ":" + callbackData);

        rowInLine.add(welcomeTextButton);
        rowInLine.add(recurrentTextButton);
        rows.add(rowInLine);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);

        try {
            telegramBot.execute(message);
        } catch (TelegramApiException e) {
            log.error(telegramBot.getError() + e.getMessage());
        }
    }

    public void banUser(Long chatId, Long commandSenderId, Long bannedUserId, String bannedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, commandSenderId)) {
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
                cleanUpAndShutDown(1, TimeUnit.MINUTES);
            }
        } else {
            messageService.sendMessage(chatId, telegramBot.getNotAnAdminError());
        }
    }

    public void warnUser(Long chatId, Long commandSenderId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, commandSenderId)) {
            User warnedUser = userService.getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                Byte warnsCount = warnedUser.getNumberOfWarns();
                if (warnsCount == null) {
                    warnedUser.setNumberOfWarns((byte) 1);
                    userRepository.save(warnedUser);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> предупрежден. \n" +
                            "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                    messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                    log.info("Пользователь " + warnedUserNickname + " предупрежден. Количество предупреждений: 1 из 3");
                } else if (warnsCount == 2) {
                    warnedUser.setNumberOfWarns((byte) 0);
                    userRepository.save(warnedUser);

                    banUser(chatId, commandSenderId, warnedUserId, warnedUserNickname, message);
                    log.info("Пользователь " + warnedUserNickname + " получил 3-е предупреждение и был забанен.");
                } else {
                    warnedUser.setNumberOfWarns((byte) (warnedUser.getNumberOfWarns() + 1));
                    userRepository.save(warnedUser);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> предупрежден. \n" +
                            "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                    messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                    log.info("Пользователь " + warnedUserNickname + " предупрежден. Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3");
                }
            } else {
                messageService.sendMessage(chatId, telegramBot.getError());
            }
        } else {
            messageService.sendMessage(chatId, telegramBot.getNotAnAdminError());
        }
    }

    public void checkWarns(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = userService.getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                Byte warnsCount = warnedUser.getNumberOfWarns();
                if (warnsCount == null) {
                    warnedUser.setNumberOfWarns((byte) 0);
                    userRepository.save(warnedUser);
                }
                String text = "Пользователь <a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> " +
                        "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";
                messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                log.info("Проверка предупреждений для пользователя " + warnedUserNickname + ": " + warnedUser.getNumberOfWarns() + " из 3");
            }
        } else {
            messageService.sendMessage(chatId, telegramBot.getNotAnAdminError());
        }
    }

    public void resetWarns(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = userService.getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                warnedUser.setNumberOfWarns((byte) 0);
                userRepository.save(warnedUser);

                String text = "Предупреждения сброшены\n" +
                        "Пользователь <a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a>\n" +
                        "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";
                messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                log.info("Предупреждения пользователя " + warnedUserNickname + " сброшены.");
            }
        } else {
            messageService.sendMessage(chatId, telegramBot.getNotAnAdminError());
        }
    }

    public void muteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (chatAdminService.isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = userService.getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                try {
                    Duration muteDuration = Duration.ofDays(1);
                    RestrictChatMember restrictChatMember = new RestrictChatMember();
                    restrictChatMember.setChatId(chatId.toString());
                    restrictChatMember.setUserId(warnedUserId);
                    restrictChatMember.setPermissions(new ChatPermissions());
                    restrictChatMember.forTimePeriodDuration(muteDuration);
                    telegramBot.execute(restrictChatMember);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> обеззвучен на сутки";
                    messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                    log.info("Пользователь " + warnedUserNickname + " обеззвучен на сутки.");
                } catch (TelegramApiException e) {
                    log.error("Ошибка при обеззвучивании пользователя: " + e.getMessage());
                }
            }
        } else {
            messageService.sendMessage(chatId, telegramBot.getNotAnAdminError());
        }
    }

    public void muteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message, boolean isAdmin) {
        if (isAdmin) {
            User warnedUser = userService.getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                try {
                    Duration muteDuration = Duration.ofDays(1);
                    RestrictChatMember restrictChatMember = new RestrictChatMember();
                    restrictChatMember.setChatId(chatId.toString());
                    restrictChatMember.setUserId(warnedUserId);
                    restrictChatMember.setPermissions(new ChatPermissions());
                    restrictChatMember.forTimePeriodDuration(muteDuration);
                    telegramBot.execute(restrictChatMember);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a> обеззвучен на сутки";
                    messageService.sendHTMLMessage(chatId, text, message.getMessageId());
                    log.info("Пользователь " + warnedUserNickname + " обеззвучен на сутки.");
                } catch (TelegramApiException e) {
                    log.error("Ошибка при обеззвучивании пользователя: " + e.getMessage());
                }
            }
        } else {
            messageService.sendMessage(chatId, telegramBot.getNotAnAdminError());
        }
    }

    public void wipeAllMessages(Long chatId) {
        Map<Long, List<Message>> userMessages = messageService.getUserMessages();

        if (!userMessages.isEmpty()) {
            for (Map.Entry<Long, List<Message>> entry : userMessages.entrySet()) {
                List<Message> messages = entry.getValue();
                for (Message message : messages) {
                    try {
                        telegramBot.execute(new org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage(
                                String.valueOf(chatId), message.getMessageId()));
                    } catch (TelegramApiException e) {
                        log.error("Ошибка при удалении сообщения: {}", e.getMessage());
                    }
                }
            }
            messageService.clearUserMessages();
            messageService.sendMessage(chatId, "Все сообщения успешно удалены");
        } else {
            messageService.sendMessage(chatId, "Нет сообщений для удаления");
        }
    }

    public void cleanUpAndShutDown(long interval, TimeUnit unit) {
        schedulerService.startBannedUsersCleanupTask(interval, unit);
        if (userService.getBannedUsers().isEmpty()) {
            schedulerService.shutdownScheduler();
        }
    }
}