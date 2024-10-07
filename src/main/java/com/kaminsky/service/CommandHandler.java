package com.kaminsky.service;

import com.kaminsky.config.BotConfig;
import com.kaminsky.finals.BotFinalVariables;
import com.kaminsky.model.KeyWord;
import com.kaminsky.model.repositories.KeyWordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.List;

@Slf4j
@Service
public class CommandHandler {

    private final UserService userService;
    private final AdminService adminService;
    private final MessageService messageService;
    private final BotConfig config;
    private final KeyWordRepository keyWordRepository;

    @Autowired
    public CommandHandler(UserService userService,
                          @Lazy AdminService adminService,
                          MessageService messageService,
                          BotConfig config,
                          KeyWordRepository keyWordRepository) {
        this.userService = userService;
        this.adminService = adminService;
        this.messageService = messageService;
        this.config = config;
        this.keyWordRepository = keyWordRepository;
    }

    public void handleMessage(Message message) {
        if (message.hasText()) {
            String messageText = message.getText().trim();
            if (messageText.startsWith("/")) {
                handleCommand(message);
            } else {
                handleNonCommandMessage(message);
            }
        }
    }

    public void handleCommand(Message message) {
        String messageText = message.getText().trim();
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();

        if (messageText.startsWith("/send") && config.getOwnerId().equals(userId)) {
            if (messageText.length() > 6) {
                String textToSend = messageText.substring(6).trim();
                userService.sendToAllUsers(textToSend);
                messageService.sendMessage(chatId, "Сообщение отправлено всем пользователям");
            } else {
                messageService.sendMessage(chatId, "Пожалуйста, укажите текст сообщения после команды /send");
            }
            return;
        }

        switch (messageText) {
            case "/start":
                userService.registerUser(message);
                messageService.startCommandReceived(chatId, message.getChat().getFirstName());
                break;
            case "/help":
                messageService.sendMessage(chatId, BotFinalVariables.HELP_TEXT);
                break;
            case "/config":
                adminService.handleConfigCommand(chatId, message);
                break;
            case "/ban":
            case "/mute":
            case "/warn":
            case "/check":
            case "/reset":
            case "/wipe":
                adminService.handleAdminCommand(chatId, userId, messageText, message);
                break;
            default:
                if (!userService.isCommandHandled()) {
                    messageService.sendMessage(chatId, BotFinalVariables.UNKNOWN_COMMAND);
                } else {
                    userService.setCommandHandled(false);
                }
                break;
        }
    }

    private void handleNonCommandMessage(Message message) {
        Long chatId = message.getChatId();
        String messageText = message.getText();

        String botUsername = config.getBotName();
        boolean isBotMentioned = messageText.contains("@" + botUsername);
        if (isBotMentioned) {
            messageService.sendMessage(chatId, "Чего надо?", message.getMessageId());
        }

        handleIncomingWelcomeTextSettingMessage(message);
        handleIncomingRecurrentTextSettingMessage(message);
        handleUnbanPetition(message);

        switch (messageText) {
            case "сайт проекта":
                String projectLink = "[Сайт проекта «Сокрытая Русь»](https://sokryt.ru)";
                messageService.sendMarkdownMessage(chatId, projectLink);
                break;
            case "петиция о разбане":
                String askText = "Сформулируйте Ваше прошение о разбане в одно сообщение";
                messageService.sendMessage(chatId, askText);
                userService.setAwaitingUnbanPetition(true);
                break;
            case "FAQ о Единоверии":
                String faq = "Единоверие - одно из течений поповского старообрядчества, находящееся под юрисдикцией Русской Православной Церкви.\n" +
                        "По определению епископа Симона (Шлеёва), «единоверие есть примирённое с Русской и Вселенской Церковью старообрядчество».\n\n" +
                        "Q: Нужно ли совершать некий чин присоединения в Единоверие для обычных прихожан РПЦ?\n" +
                        "A: Нет, достаточно просто начать ходить в ближайший единоверческий приход.\n\n" +
                        "Q: Почитают ли единоверцы послераскольных святых?\n" +
                        "A: Согласно поместному собору Русской Православной Церкви 1918 г., единоверцы совершают богослужения исключительно по старопечатным книгам. " +
                        "Таким образом, на единоверческих службах послераскольные святые не упоминаются, однако как чада Русской Православной Церкви, единоверцы почитают и признают святость всех святых, канонизированных РПЦ.";
                messageService.sendMessage(chatId, faq);
                break;
            case "вступить в чат":
                String mapLink = "[Главный чат Общества](https://t.me/ukhtomsky_chat)";
                messageService.sendMarkdownMessage(chatId, mapLink);
                break;
        }

        List<String> keyWords = getKeyWords();
        if (isContainKeyWords(keyWords, messageText)) {
            messageService.sendRandomGif(chatId);
            adminService.muteUser(chatId, message.getFrom().getId(), message.getFrom().getFirstName(), message, true);
        }
    }

    public void sayFarewellToUser(Message message) {
        org.telegram.telegrambots.meta.api.objects.User leftUser = message.getLeftChatMember();
        long chatId = message.getChatId();

        String userFirstName = leftUser.getFirstName();
        String userLink = "<a href=\"tg://user?id=" + leftUser.getId() + "\">" + userFirstName + "</a>";
        String farewellMessage = "Всего хорошего, " + userLink;

        messageService.sendHTMLMessage(chatId, farewellMessage, message.getMessageId());
    }

    public void handleIncomingWelcomeTextSettingMessage(Message message) {
        Long chatId = message.getChatId();
        String text = message.getText();
        if (userService.isAwaitingWelcomeText() && userService.getCurrentChatIdForWelcomeText() != null) {
            userService.saveWelcomeText(userService.getCurrentChatIdForWelcomeText(), text);
            userService.setAwaitingWelcomeText(false);
            userService.setCurrentChatIdForWelcomeText(null);
            messageService.sendMessage(chatId, "Приветственное сообщение успешно сохранено!");
            userService.setCommandHandled(true);
        }
    }

    public void handleIncomingRecurrentTextSettingMessage(Message message) {
        Long chatId = message.getChatId();
        String text = message.getText();
        if (userService.isAwaitingRecurrentText() && userService.getCurrentChatIdForRecurrentText() != null) {
            userService.saveRecurrentText(userService.getCurrentChatIdForRecurrentText(), text);
            userService.setAwaitingRecurrentText(false);
            userService.setCurrentChatIdForRecurrentText(null);
            messageService.sendMessage(chatId, "Рекуррентное сообщение успешно сохранено!");
            userService.setCommandHandled(true);
        }
    }
    public void handleUnbanPetition(Message message) {
        if (userService.isAwaitingUnbanPetition()) {
            Long chatId = message.getChatId();
            userService.forwardUnbanPetition(chatId, message);
            messageService.sendMessage(chatId, "Ваше сообщение отправлено администрации. С Вами свяжутся");
            userService.setAwaitingUnbanPetition(false);
            userService.setCommandHandled(true);
        }
    }

    private boolean isContainKeyWords(List<String> words, String messageText) {
        String text = messageText.toLowerCase();
        for (String word : words) {
            if (text.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<String> getKeyWords() {
        Iterable<KeyWord> keyWords = keyWordRepository.findAll();
        return ((List<KeyWord>) keyWords).stream()
                .map(KeyWord::getKeyWord)
                .toList();
    }
}