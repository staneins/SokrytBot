package com.kaminsky.service;

import com.kaminsky.config.BotConfig;
import com.kaminsky.model.Ad;
import com.kaminsky.model.AdRepository;
import com.kaminsky.model.User;
import com.kaminsky.model.UserRepository;
import com.vdurmont.emoji.EmojiParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllChatAdministrators;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllGroupChats;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdRepository adRepository;

    final BotConfig config;

    private Map<Long, Boolean> userCaptchaStatus = new HashMap<>();

    private Map<Long, List<Message>> userMessages = new HashMap<>();

    private Set<Long> bannedUsers = new HashSet<>();

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    static final String ERROR = "Ошибка ";

    static final String UNKNOWN_COMMAND = "Мне незнакома эта команда.";

    static final String HELP_TEXT = "Этот бот пока ничего не умеет, кроме приветствий.\n" +
                                    "Вы можете увидеть список будущих команд в меню слева.";

    static final String NOT_ADMIN_ERROR = "Для этого нужны права адмистратора.";

    static final String CONFIRM_BUTTON = "CONFIRM_BUTTON";

    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> privateChatCommands = new ArrayList<>();
        privateChatCommands.add(new BotCommand("/start", "запустить бот"));
        privateChatCommands.add(new BotCommand("/mydata", "данные о пользователе"));
        privateChatCommands.add(new BotCommand("/deletedata", "удалить данные о пользователе"));
        privateChatCommands.add(new BotCommand("/help", "описание работы бота"));
        privateChatCommands.add(new BotCommand("/settings", "установить настройки"));

        List<BotCommand> publicChatCommands = new ArrayList<>();
        publicChatCommands.add(new BotCommand("/ban", "забанить пользователя"));
        publicChatCommands.add(new BotCommand("/mute", "обеззвучить пользователя"));
        publicChatCommands.add(new BotCommand("/warn", "предупредить пользователя"));
        publicChatCommands.add(new BotCommand("/check", "посмотреть количество предупреждений"));
        publicChatCommands.add(new BotCommand("/reset", "сбросить предупреждения"));
        publicChatCommands.add(new BotCommand("/wipe", "очистить историю сообщений"));

        try {
            this.execute(new SetMyCommands(privateChatCommands, new BotCommandScopeAllPrivateChats(), null));
            this.execute(new SetMyCommands(publicChatCommands, new BotCommandScopeAllChatAdministrators(), null));
        } catch (TelegramApiException e) {
            log.error("Ошибка при написании списка команд: " + e.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }

        String botUsername = getBotUsername();

//        boolean isBotMentioned = update.getMessage().getText().contains("@" + botUsername);

        boolean isReplyToBot = update.getMessage().isReply() &&
                update.getMessage().getReplyToMessage().getFrom().getUserName().equals(botUsername);

        if (update.hasMessage() && update.getMessage().getNewChatMembers() != null && !update.getMessage().getNewChatMembers().isEmpty()) {
            long chatId = update.getMessage().getChatId();
            popupCaptcha(update, chatId);
        }

        if (update.hasMessage()) {
            String messageText = "";
            Message message = update.getMessage();
            Long userId = message.getFrom().getId();

            if (message.getLeftChatMember() != null && !bannedUsers.contains(message.getLeftChatMember().getId())) {
                sayFarewellToUser(message);
            }

            userMessages.putIfAbsent(userId, new ArrayList<>());
            userMessages.get(userId).add(message);

            if (message.hasText()) {
                messageText = message.getText();
            }

            boolean isGroupChat = update.getMessage().getChat().isGroupChat() || update.getMessage().getChat().isSuperGroupChat();
            boolean isPrivateChat = update.getMessage().getChat().isUserChat();

            long chatId = update.getMessage().getChatId();

            if (isPrivateChat) {
                handlePrivateCommand(chatId, messageText, update);
            }

            if (isGroupChat && (isReplyToBot || update.getMessage().getText().contains("@" + botUsername))) {
                handleGroupChatCommand(chatId, messageText, update);
            }
        }
    }

    private void handlePrivateCommand(long chatId, String messageText, Update update) {
        if (messageText.contains("/send") && config.getOwnerId() == chatId) {
            String textToSend = EmojiParser.parseToUnicode(messageText.substring(messageText.indexOf(" ")));
            Iterable<User> users = userRepository.findAll();
            for (User user : users) {
                prepareAndSendMessage(user.getChatId(), textToSend);
            }
        } else {
            switch (messageText) {
                case "/start":
                    registerUser(update.getMessage());
                    startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                    break;
                case "/help":
                    prepareAndSendMessage(chatId, HELP_TEXT);
                    break;
                default:
                    prepareAndSendMessage(chatId, UNKNOWN_COMMAND);
            }
        }
    }

    private void handleGroupChatCommand(long chatId, String messageText, Update update) {
        Long commandSenderId = update.getMessage().getFrom().getId();
        Long objectId = update.getMessage().getReplyToMessage().getFrom().getId();
        String objectName = update.getMessage().getReplyToMessage().getFrom().getFirstName();
        Message message = update.getMessage();

            switch (messageText) {
                case "/ban@sokrytbot":
                    banUser(chatId, commandSenderId, objectId, objectName, message);
                    log.info("Забанили " + update.getMessage().getFrom().getUserName());
                    break;
                case "/warn@sokrytbot":
                    warnUser(chatId, commandSenderId, objectId, objectName, message);
                    break;
                case "/mute@sokrytbot":
                    muteUser(chatId, objectId, objectName, message);
                    break;
                case "/check@sokrytbot":
                    checkWarns(chatId, objectId, objectName, message);
                    break;
                case "/reset@sokrytbot":
                    resetWarns(chatId, objectId, objectName, message);
                    break;
                case "/wipe@sokrytbot":
                    wipeAllMessages();
                    break;
                default:
                    prepareAndSendMessage(chatId, "Мне незнакома эта команда", update.getMessage().getMessageId());
            }
        }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        log.info("Получен callbackQuery с данными: " + callbackQuery.getData());
        String callbackData = callbackQuery.getData();
        long userId = callbackQuery.getFrom().getId();
        long chatId = callbackQuery.getMessage().getChatId();
        long messageId = callbackQuery.getMessage().getMessageId();
        String userFirstName = callbackQuery.getFrom().getFirstName();
        String userLink = "<a href=\"tg://user?id=" + userId + "\">" + userFirstName + "</a>";

        String[] callbackDataParts = callbackData.split(":");
        String button = callbackDataParts[0];
        long targetUserId = Long.parseLong(callbackDataParts[1]);

        if (button.equals(CONFIRM_BUTTON) && targetUserId == userId) {
            userCaptchaStatus.put(userId, true);
            try {
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(String.valueOf(chatId));
                editMessage.setMessageId((int) messageId);
                editMessage.setText("Добро пожаловать, " + userLink);
                editMessage.setParseMode("HTML");
                execute(editMessage);
            } catch (TelegramApiException e) {
                log.error(ERROR + e.getMessage());
            }
        }
    }



//    private void register(Long chatId) {
//        SendMessage message = new SendMessage();
//        message.setChatId(chatId.toString());
//        message.setText("Вы действительно хотите зарегистрироваться?");
//
//        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
//        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
//        List<InlineKeyboardButton> rowInLine = new ArrayList<>();
//        InlineKeyboardButton yesButton = new InlineKeyboardButton();
//
//        yesButton.setText("Да");
//        yesButton.setCallbackData(YES_BUTTON);
//
//        InlineKeyboardButton noButton = new InlineKeyboardButton();
//
//        noButton.setText("Нет");
//        noButton.setCallbackData(NO_BUTTON);
//
//        rowInLine.add(yesButton);
//        rowInLine.add(noButton);
//
//        rows.add(rowInLine);
//
//        markup.setKeyboard(rows);
//
//        message.setReplyMarkup(markup);
//
//        executeMessage(message);
//
//    }

    private void registerUser(Message message) {
        if (message != null && message.getChat() != null) {
            if (userRepository.findById(message.getFrom().getId()).isEmpty()) {
                Long chatId = message.getFrom().getId();
                Chat chat = message.getChat();

                User user = new User();
                user.setChatId(chatId);
                user.setFirstName(chat.getFirstName());
                user.setLastName(chat.getLastName());
                user.setUserName(chat.getUserName());
                user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));

                userRepository.save(user);
                log.info("Пользователь сохранен: " + user);
            }
        } else {
            log.warn("Попытка зарегистрировать пользователя с пустым сообщением или чатом");
        }
    }

    public List<Message> getUserMessages(Long userId) {
        return userMessages.getOrDefault(userId, new ArrayList<>());
    }

    private void startCommandReceived(Long chatId, String name) {
        String answer = EmojiParser.parseToUnicode("Доброго здоровья, " + name + "!" + " :smiley:");
        prepareAndSendMessage(chatId, answer);
        log.info("Ответил пользователю " + name);
    }

    private void banUser(Long chatId, Long commandSenderId, Long bannedUserId, String bannedUserNickname, Message message) {
        try {
            if (isAdmin(chatId, commandSenderId)) {
                if (isAdmin(chatId, bannedUserId)) {
                    prepareAndSendMessage(chatId, "Не могу забанить администратора");
                } else {
                    execute(new BanChatMember(String.valueOf(chatId), bannedUserId));
                    String text = "<a href=\"tg://user?id=" + bannedUserId + "\">" + bannedUserNickname + "</a>" +
                            " уничтожен";
                    prepareAndSendHTMLMessage(chatId, text, message.getMessageId());
                    bannedUsers.add(bannedUserId);
                    cleanUpAndShutDown(1, TimeUnit.MINUTES);
                }
            } else {
                prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
            }
        } catch (TelegramApiException e) {
            log.error(ERROR + e.getMessage());
        }
    }

    private void warnUser(Long chatId, Long commandSenderId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                Byte warnsCount = warnedUser.getNumberOfWarns();
                if (warnsCount == null) {
                    warnedUser.setNumberOfWarns((byte) 1);

                    userRepository.save(warnedUser);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a>" +
                            " предупрежден. \n" + "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                    prepareAndSendHTMLMessage(chatId, text, message.getMessageId());

                } else if (warnsCount == 2) {
                    warnedUser.setNumberOfWarns((byte) 0);

                    userRepository.save(warnedUser);

                    banUser(chatId, commandSenderId, warnedUserId, warnedUserNickname, message);
                } else {
                    warnedUser.setNumberOfWarns((byte) (warnedUser.getNumberOfWarns() + 1));

                    userRepository.save(warnedUser);

                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a>" +
                            " предупрежден. \n" + "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                    prepareAndSendHTMLMessage(chatId, text, message.getMessageId());
                }
            } else {
                prepareAndSendMessage(chatId, ERROR);
            }
        } else {
            prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
        }
    }

    private void checkWarns(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                Byte warnsCount = warnedUser.getNumberOfWarns();

                if (warnsCount == null) {
                    warnedUser.setNumberOfWarns((byte) 0);

                    userRepository.save(warnedUser);
                }
                String text = "Пользователь " + "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a>" +
                        " Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3";

                prepareAndSendHTMLMessage(chatId, text, message.getMessageId());
                }
            } else {
            prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
        }
    }

    private void resetWarns(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                    warnedUser.setNumberOfWarns((byte) 0);
                    userRepository.save(warnedUser);

                String text = "Предупреждения сброшены\n" + "Пользователь "
                        + "<a href=\"tg://user?id=" + warnedUserId + "\">"
                        + warnedUserNickname + "</a>" +
                        "\nКоличество предупреждений: " +
                        warnedUser.getNumberOfWarns() + " из 3";

                prepareAndSendHTMLMessage(chatId, text, message.getMessageId());
            }
        } else {
            prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
        }
    }

    private void muteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            if (warnedUser != null) {
                try {
                    Duration muteDuration = Duration.ofDays(1);
                    RestrictChatMember restrictChatMember = new RestrictChatMember(String.valueOf(chatId), warnedUserId, new ChatPermissions());
                    restrictChatMember.forTimePeriodDuration(muteDuration);
                    execute(restrictChatMember);
                    String text = "<a href=\"tg://user?id=" + warnedUserId + "\">" + warnedUserNickname + "</a>" +
                            " обеззвучен на сутки";
                    prepareAndSendHTMLMessage(chatId, text, message.getMessageId());
                } catch (TelegramApiException e) {
                    log.error(ERROR + e.getMessage());
                }
            }
        } else {
            prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
        }
    }

    private void wipeAllMessages() {
        if (!userMessages.isEmpty()) {
            for (Map.Entry<Long, List<Message>> entry : userMessages.entrySet()) {
                List<Message> messages = entry.getValue();
                for (Message message : messages) {
                    DeleteMessage deleteMessage = new DeleteMessage();
                    deleteMessage.setMessageId(message.getMessageId());
                    deleteMessage.setChatId(String.valueOf(message.getChatId()));
                    try {
                        execute(deleteMessage);
                        userMessages.clear();
                    } catch (TelegramApiException e) {
                        log.error(ERROR + e.getMessage());
                    }
                }
            }
        }
    }

    private void reactOnKeyWords() {

    }


    private void popupCaptcha(Update update, long chatId) {
        Message msg = update.getMessage();
        if (msg.getNewChatMembers() != null && !msg.getNewChatMembers().isEmpty()) {
            List<org.telegram.telegrambots.meta.api.objects.User> newMembers = msg.getNewChatMembers();
            for (org.telegram.telegrambots.meta.api.objects.User newMember : newMembers) {

                Long userId = newMember.getId();
                String userFirstName = newMember.getFirstName();

                userCaptchaStatus.put(userId, false);

                SendMessage message = new SendMessage();
                String userLink = "<a href=\"tg://user?id=" + userId + "\">" + userFirstName + "</a>" +
                        ", нажмите кнопку в течение 3-х минут, чтобы войти в чат";
                message.setParseMode("HTML");
                message.setChatId(chatId);
                message.setText(userLink);

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> rowInLine = new ArrayList<>();
                InlineKeyboardButton confirmButton = new InlineKeyboardButton();

                String answer = EmojiParser.parseToUnicode(":point_right:" + "Я не бот" + ":point_left:");
                confirmButton.setText(answer);
                confirmButton.setCallbackData(CONFIRM_BUTTON + ":" + userId);

                rowInLine.add(confirmButton);
                rows.add(rowInLine);
                markup.setKeyboard(rows);

                message.setReplyMarkup(markup);

                try {
                    Message sentMessage = execute(message);
                    long sentMessageId = sentMessage.getMessageId();

                    scheduleKickTask(chatId, newMember.getId(), sentMessageId);
                } catch (TelegramApiException e) {
                    log.error(ERROR + e.getMessage());
                }
            }
        }
    }

    private void scheduleKickTask(long chatId, long userId, long messageId) {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                if (!userCaptchaStatus.getOrDefault(userId, false)) {
                    try {
                        BanChatMember kickChatMember = new BanChatMember();
                        Duration kickDuration = Duration.ofNanos(1);
                        kickChatMember.forTimePeriodDuration(kickDuration);
                        kickChatMember.setChatId(String.valueOf(chatId));
                        kickChatMember.setUserId(userId);
                        execute(kickChatMember);
                        bannedUsers.add(userId);
                        cleanUpAndShutDown(1, TimeUnit.MINUTES);

                        DeleteMessage deleteMessage = new DeleteMessage();
                        deleteMessage.setChatId(String.valueOf(chatId));
                        deleteMessage.setMessageId((int) messageId);
                        execute(deleteMessage);

                        List<Message> kickedUserMessages = getUserMessages(userId);

                        for (Message kickedUserMessage : kickedUserMessages) {
                            deleteMessage = new DeleteMessage();
                            deleteMessage.setChatId(String.valueOf(chatId));
                            deleteMessage.setMessageId(kickedUserMessage.getMessageId());
                            execute(deleteMessage);
                        }

                    } catch (TelegramApiException e) {
                        log.error(ERROR + e.getMessage());
                    }
                }
            }
        };
        Timer timer = new Timer();
        timer.schedule(task, 30000);
    }

    private void sayFarewellToUser(Message message) {
        org.telegram.telegrambots.meta.api.objects.User leftUser = message.getLeftChatMember();
        long chatId = message.getChatId();

        String userFirstName = leftUser.getFirstName();
        String userLink = "<a href=\"tg://user?id=" + leftUser.getId() + "\">" + userFirstName + "</a>";
        String farewellMessage = "Всего хорошего, " + userLink;

        prepareAndSendHTMLMessage(chatId, farewellMessage, message.getMessageId());
    }

    private void clearBannedUsers() {
        bannedUsers.clear();
        log.info("Список забаненных пользователей очищен");
    }

    private void cleanUpAndShutDown(long interval, TimeUnit timeUnit) {
        startBannedUsersCleanupTask(interval, timeUnit);
        if (bannedUsers.isEmpty()) {
            stopScheduler();
        }
    }

    private void startBannedUsersCleanupTask(long interval, TimeUnit timeUnit) {
        scheduler.scheduleAtFixedRate(() -> {
            clearBannedUsers();
        }, interval, interval, timeUnit);
    }

    public void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    private User getOrRegisterWarnedUser(Message message, Long warnedUserId) {
        registerUser(message.getReplyToMessage());
        return userRepository.findById(warnedUserId).orElse(null);
    }

    private boolean isAdmin(Long chatId, Long userId) {
        try {
            GetChatAdministrators getChatAdministrators = new GetChatAdministrators();
            getChatAdministrators.setChatId(chatId);
            List<ChatMember> administrators = execute(getChatAdministrators);
            return administrators.stream()
                    .anyMatch(admin -> admin.getUser().getId().equals(userId));
        } catch (TelegramApiException e) {
            log.error(ERROR + e.getMessage());
            return false;
        }
    }


//    private void keyboardMethod(long chatId, String textToSend) {
//
//        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
//        List<KeyboardRow> keyboardRows = new ArrayList<>();
//        KeyboardRow row = new KeyboardRow();
//
//        row.add("петиция о разбане");
//        row.add("сайт проекта");
//
//        keyboardRows.add(row);
//
//        row = new KeyboardRow();
//
//        row.add("FAQ о Единоверии");
//        row.add("карта приходов");
//        row.add("обучение");
//
//        keyboardRows.add(row);
//
//        keyboardMarkup.setKeyboard(keyboardRows);
//
//        message.setReplyMarkup(keyboardMarkup);
//
//        executeMessage(message);
//    }

    private void executeEditMessageText(String text, long chatId, long messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(chatId);
        message.setText(text);
        message.setMessageId((int) messageId);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR + e.getMessage());
        }
    }

    private void executeMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR + e.getMessage());
        }
    }

    private void prepareAndSendMessage(Long chatId, String textToSend){
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(textToSend);
        executeMessage(message);
    }

    private void prepareAndSendMessage(Long chatId, String textToSend, Integer replyToMessageId){
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(textToSend);
        message.setReplyToMessageId(replyToMessageId);
        executeMessage(message);
    }

    private void prepareAndSendHTMLMessage(Long chatId, String textToSend, Integer replyToMessageId){
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(textToSend);
        message.setReplyToMessageId(replyToMessageId);
        message.setParseMode("HTML");
        executeMessage(message);
    }

    @Scheduled(cron = "${cron.scheduler}")
    private void sendAd(){
        Iterable<Ad> ads = adRepository.findAll();
        Iterable<User> users = userRepository.findAll();

        for (Ad ad : ads) {
            for (User user : users) {
                prepareAndSendMessage(user.getChatId(), ad.getAd());
            }
        }
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }
}
