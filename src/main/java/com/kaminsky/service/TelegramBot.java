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

        try {
            this.execute(new SetMyCommands(privateChatCommands, new BotCommandScopeAllPrivateChats(), null));
            this.execute(new SetMyCommands(publicChatCommands, new BotCommandScopeAllChatAdministrators(), null));
        } catch (TelegramApiException e) {
            log.error("Ошибка при написании списка команд: " + e.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        String botUsername = getBotUsername();

//        boolean isBotMentioned = update.getMessage().getText().contains("@" + botUsername);

        boolean isReplyToBot = update.getMessage().isReply() &&
                update.getMessage().getReplyToMessage().getFrom().getUserName().equals(botUsername);

        if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            String callbackData = callbackQuery.getData();
            long userId = callbackQuery.getFrom().getId();
            long chatId = callbackQuery.getMessage().getChatId();
            long messageId = callbackQuery.getMessage().getMessageId();

            if (callbackData.equals(CONFIRM_BUTTON)) {
                userCaptchaStatus.put(userId, true);

                try {
                    String text = "Добро пожаловать";
                    EditMessageText editMessage = new EditMessageText();
                    editMessage.setChatId(String.valueOf(chatId));
                    editMessage.setMessageId((int) messageId);
                    editMessage.setText(text);
                    execute(editMessage);

                } catch (TelegramApiException e) {
                    log.error(ERROR + e.getMessage());
                }
            }
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            Message message = update.getMessage();
            Long userId = message.getFrom().getId();

            userMessages.putIfAbsent(userId, new ArrayList<>());
            userMessages.get(userId).add(message);

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

        if (update.hasMessage() && update.getMessage().getNewChatMembers() != null && !update.getMessage().getNewChatMembers().isEmpty()) {
            long chatId = update.getMessage().getChatId();
            popupCaptcha(update, chatId);
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
                default:
                    prepareAndSendMessage(chatId, "Мне незнакома эта команда");
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
                    String text = bannedUserNickname + " уничтожен.";
                    prepareAndSendMessage(chatId, text, message.getReplyToMessage().getMessageId());
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

                    prepareAndSendMessage(chatId, warnedUserNickname + " предупрежден. \n" +
                            "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3", message.getReplyToMessage().getMessageId());

                } else if (warnsCount == 2) {
                    warnedUser.setNumberOfWarns((byte) 0);

                    userRepository.save(warnedUser);

                    banUser(chatId, commandSenderId, warnedUserId, warnedUserNickname, message);
                } else {
                    warnedUser.setNumberOfWarns((byte) (warnedUser.getNumberOfWarns() + 1));

                    userRepository.save(warnedUser);

                    prepareAndSendMessage(chatId, warnedUserNickname + " предупрежден. \n" +
                            "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3", message.getReplyToMessage().getMessageId());
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
                prepareAndSendMessage(chatId, "Пользователь " + warnedUserNickname + "\n" +
                        "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3", message.getReplyToMessage().getMessageId());
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

                prepareAndSendMessage(chatId, "Предупреждения сброшены\n" + "Пользователь " + warnedUserNickname + "\n" +
                        "Количество предупреждений: " + warnedUser.getNumberOfWarns() + " из 3", message.getReplyToMessage().getMessageId());
            }
        } else {
            prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
        }
    }

    private void muteUser(Long chatId, Long warnedUserId, String warnedUserNickname, Message message) {
        if (isAdmin(chatId, message.getFrom().getId())) {
            User warnedUser = getOrRegisterWarnedUser(message, warnedUserId);
            Integer untilDate = 86400;
            if (warnedUser != null) {
                try {
                    execute(new RestrictChatMember(String.valueOf(chatId) ,warnedUserId, new ChatPermissions(), untilDate, false));
                    String text = warnedUserNickname + " обеззвучен.";
                    prepareAndSendMessage(chatId, text, message.getReplyToMessage().getMessageId());
                } catch (TelegramApiException e) {
                    log.error(ERROR + e.getMessage());
                }
            }
        } else {
            prepareAndSendMessage(chatId, NOT_ADMIN_ERROR);
        }
    }

    private void popupCaptcha(Update update, long chatId) {
        Message msg = update.getMessage();
        if (msg.getNewChatMembers() != null && !msg.getNewChatMembers().isEmpty()) {
            List<org.telegram.telegrambots.meta.api.objects.User> newMembers = msg.getNewChatMembers();
            for (org.telegram.telegrambots.meta.api.objects.User newMember : newMembers) {

                userCaptchaStatus.put(newMember.getId(), false);

                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Нажмите кнопку, чтобы войти в чат");

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> rowInLine = new ArrayList<>();
                InlineKeyboardButton confirmButton = new InlineKeyboardButton();

                String answer = EmojiParser.parseToUnicode(":point_right:" + "Я не бот" + ":point_left: ");
                confirmButton.setText(answer);
                confirmButton.setCallbackData(CONFIRM_BUTTON);

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

                        DeleteMessage deleteMessage = new DeleteMessage();
                        deleteMessage.setChatId(String.valueOf(chatId));
                        deleteMessage.setMessageId((int) messageId);
                        execute(deleteMessage);

                    } catch (TelegramApiException e) {
                        log.error(ERROR + e.getMessage());
                    }
                }
            }
        };

        Timer timer = new Timer();
        timer.schedule(task, 180000);
    }


    private User getOrRegisterWarnedUser(Message message, Long warnedUserId) {
        registerUser(message.getReplyToMessage());
        return userRepository.findById(warnedUserId).orElse(null);
    }

    private boolean isAdministator(Long chatId, Long userId) {
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

    private boolean isAdmin(Long chatId, Long userId) {
        return isAdministator(chatId, userId);
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
