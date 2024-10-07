package com.kaminsky.service;

import com.kaminsky.model.BotMessage;
import com.kaminsky.model.repositories.BotMessageRepository;
import com.vdurmont.emoji.EmojiParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CaptchaService {

    private final UserService userService;
    private final MessageService messageService;
    private final BotMessageRepository botMessageRepository;
    private final SchedulerService schedulerService;
    private final AdminService adminService;

    @Autowired
    public CaptchaService(UserService userService,
                          MessageService messageService,
                          BotMessageRepository botMessageRepository,
                          SchedulerService schedulerService, AdminService adminService) {
        this.userService = userService;
        this.messageService = messageService;
        this.botMessageRepository = botMessageRepository;
        this.schedulerService = schedulerService;
        this.adminService = adminService;
    }

    public void popupCaptcha(Update update, long chatId, TelegramLongPollingBot bot) {
        Message msg = update.getMessage();
        if (msg.getNewChatMembers() != null && !msg.getNewChatMembers().isEmpty()) {
            List<User> newMembers = msg.getNewChatMembers();
            for (org.telegram.telegrambots.meta.api.objects.User newMember : newMembers) {
                long userId = newMember.getId();
                String userFirstName = newMember.getFirstName();

                userService.setUserCaptchaStatus(userId, false);

                SendMessage message = new SendMessage();
                String userLink = "<a href=\"tg://user?id=" + userId + "\">" + userFirstName + "</a>" +
                        ", нажмите кнопку в течение 3-х минут, чтобы войти в чат";
                message.setParseMode("HTML");
                message.setChatId(String.valueOf(chatId));
                message.setText(userLink);

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> rowInLine = new ArrayList<>();
                InlineKeyboardButton confirmButton = new InlineKeyboardButton();

                String answer = EmojiParser.parseToUnicode(":point_right:" + "Я не бот" + ":point_left:");
                confirmButton.setText(answer);
                confirmButton.setCallbackData("CONFIRM_BUTTON" + ":" + userId);

                rowInLine.add(confirmButton);
                rows.add(rowInLine);
                markup.setKeyboard(rows);

                message.setReplyMarkup(markup);

                try {
                    Message sentMessage = bot.execute(message);
                    handleCaptchaTimeout(chatId, newMember.getId(), sentMessage.getMessageId());
                } catch (TelegramApiException e) {
                    log.error("Ошибка при отправке кнопки: {}", e.getMessage());
                }
            }
        }
    }

    public void handleConfirmButton(CallbackQuery callbackQuery) {
        log.info("Пользователь нажал на кнопку вступления в чат: " + callbackQuery.getFrom().getId());

        String callbackData = callbackQuery.getData();
        String[] parts = callbackData.split(":");

        if (parts.length != 2) {
            log.error("Неверный формат callback data: " + callbackData);
            return;
        }

        String button = parts[0];
        Long targetUserId;

        try {
            targetUserId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.error("Неверный id пользователя: " + parts[1]);
            return;
        }

        if (!button.equals("CONFIRM_BUTTON")) {
            log.warn("Неизвестный тип кнопки: " + button);
            return;
        }

        Long userId = callbackQuery.getFrom().getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();
        String userFirstName = callbackQuery.getFrom().getFirstName();
        String userLink = "[" + userFirstName + "](tg://user?id=" + userId + ")";

        if (targetUserId.equals(userId)) {
            userService.setUserCaptchaStatus(userId, true);
            Optional<BotMessage> botMessageOptional = botMessageRepository.findById(chatId);
            String welcomeMessage = botMessageOptional
                    .map(BotMessage::getWelcomeMessage)
                    .orElse("");

            String text = "Добро пожаловать, " + userLink + "\n" + messageService.fixMarkdownText(welcomeMessage);

            EditMessageText editMessage = new EditMessageText();
            editMessage.setChatId(chatId.toString());
            editMessage.setMessageId(messageId);
            editMessage.setText(text);
            editMessage.setParseMode("MarkdownV2");

            messageService.executeEditMessage(editMessage);

        } else {
            log.warn("ID пользователя не совпадает с ID в Callback " + userId + " != " + targetUserId);
        }
    }

    public void handleCaptchaTimeout(Long chatId, Long userId, Integer messageId) {
        schedulerService.scheduleTask(() -> {
            boolean isCaptchaConfirmed = userService.getUserCaptchaStatus(userId);
            if (!isCaptchaConfirmed) {
                BanChatMember kickChatMember = new BanChatMember();
                Duration kickDuration = Duration.ofNanos(1);
                kickChatMember.setChatId(chatId.toString());
                kickChatMember.setUserId(userId);
                kickChatMember.forTimePeriodDuration(kickDuration);
                adminService.cleanUpAndShutDown(1, TimeUnit.MINUTES);

                messageService.executeBanChatMember(kickChatMember);
                log.info("Пользователь {} не прошел каптчу и был кикнут", userId);

                DeleteMessage deleteMessage = new DeleteMessage();
                deleteMessage.setChatId(String.valueOf(chatId));
                deleteMessage.setMessageId(messageId);

                messageService.executeDeleteMessage(deleteMessage);

                messageService.deleteUserMessages(chatId, userId);

            }
        }, 3, TimeUnit.MINUTES);
    }
}
