package telegram.files;

import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;

import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class TelegramChats {
    private static final Log log = LogFactory.get();

    private final TelegramGateway client;

    private final ConcurrentMap<Long, TdApi.Chat> chats = new ConcurrentHashMap<>();

    private final NavigableSet<OrderedChat> mainChatList = new TreeSet<>();

    private final NavigableSet<OrderedChat> archivedChatList = new TreeSet<>();

    private boolean haveFullMainChatList = false;

    private boolean haveFullArchivedChatList = false;

    public TelegramChats(TelegramGateway client) {
        this.client = client;
    }

    public List<TdApi.Chat> getChatList(Long activatedChatId, String query, int limit, boolean archived) {
        List<TdApi.Chat> chatList = (archived ? archivedChatList : mainChatList).stream()
                .map(OrderedChat::chatId)
                .map(chats::get)
                .filter(Objects::nonNull)
                .filter(chat -> StrUtil.isBlank(query) || chat.title.contains(query))
                .limit(limit)
                .collect(Collectors.toList());

        if (activatedChatId != null) {
            TdApi.Chat activatedChat = chats.get(activatedChatId);
            if (activatedChat != null && !chatList.contains(activatedChat)) {
                chatList.addFirst(activatedChat);
            }
        }

        return chatList;
    }

    public TdApi.Chat getChat(long chatId) {
        return chats.get(chatId);
    }

    public void loadMainChatList() {
        synchronized (mainChatList) {
            if (!haveFullMainChatList) {
                // send LoadChats request if there are some unknown chats and have not enough known chats
                client.execute(new TdApi.LoadChats(new TdApi.ChatListMain(), 100))
                        .onSuccess(_ -> {
                            // chats had already been received through updates, let's retry request
                            loadMainChatList();
                        })
                        .onFailure(error -> {
                            if (((TelegramRunException) error).getError().code == 404) {
                                synchronized (mainChatList) {
                                    haveFullMainChatList = true;
                                    log.debug("Main chat list is loaded, size: %d".formatted(mainChatList.size()));
                                }
                            }
                        });
            }
        }
    }

    public void loadArchivedChatList() {
        synchronized (archivedChatList) {
            if (!haveFullArchivedChatList) {
                // send LoadChats request if there are some unknown chats and have not enough known chats
                client.execute(new TdApi.LoadChats(new TdApi.ChatListArchive(), 100))
                        .onSuccess(_ -> {
                            // chats had already been received through updates, let's retry request
                            loadArchivedChatList();
                        })
                        .onFailure(error -> {
                            if (((TelegramRunException) error).getError().code == 404) {
                                synchronized (archivedChatList) {
                                    haveFullArchivedChatList = true;
                                    log.debug("Archived chat list is loaded, size: %d".formatted(archivedChatList.size()));
                                }
                            }
                        });
            }
        }
    }

    public void onChatUpdated(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.UpdateNewChat.CONSTRUCTOR: {
                TdApi.UpdateNewChat updateNewChat = (TdApi.UpdateNewChat) object;
                TdApi.Chat chat = updateNewChat.chat;
                synchronized (chat) {
                    chats.put(chat.id, chat);

                    TdApi.ChatPosition[] positions = chat.positions;
                    setChatPositions(chat, positions);
                }
                break;
            }
            case TdApi.UpdateChatTitle.CONSTRUCTOR: {
                TdApi.UpdateChatTitle updateChat = (TdApi.UpdateChatTitle) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                synchronized (chat) {
                    chat.title = updateChat.title;
                }
                break;
            }
            case TdApi.UpdateChatPhoto.CONSTRUCTOR: {
                TdApi.UpdateChatPhoto updateChat = (TdApi.UpdateChatPhoto) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                synchronized (chat) {
                    chat.photo = updateChat.photo;
                }
                break;
            }
            case TdApi.UpdateChatReadInbox.CONSTRUCTOR: {
                TdApi.UpdateChatReadInbox updateChat = (TdApi.UpdateChatReadInbox) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                synchronized (chat) {
                    chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId;
                    chat.unreadCount = updateChat.unreadCount;
                }
                break;
            }
            case TdApi.UpdateChatLastMessage.CONSTRUCTOR: {
                TdApi.UpdateChatLastMessage updateChat = (TdApi.UpdateChatLastMessage) object;
                TdApi.Chat chat = chats.get(updateChat.chatId);
                synchronized (chat) {
                    chat.lastMessage = updateChat.lastMessage;
                    setChatPositions(chat, updateChat.positions);
                }
                break;
            }
            case TdApi.UpdateChatPosition.CONSTRUCTOR: {
                TdApi.UpdateChatPosition updateChat = (TdApi.UpdateChatPosition) object;
                if (updateChat.position.list.getConstructor() != TdApi.ChatListMain.CONSTRUCTOR
                    && updateChat.position.list.getConstructor() != TdApi.ChatListArchive.CONSTRUCTOR) {
                    break;
                }

                TdApi.Chat chat = chats.get(updateChat.chatId);
                synchronized (chat) {
                    int i;
                    for (i = 0; i < chat.positions.length; i++) {
                        if (chat.positions[i].list.getConstructor() == updateChat.position.list.getConstructor()) {
                            break;
                        }
                    }
                    TdApi.ChatPosition[] new_positions = new TdApi.ChatPosition[chat.positions.length + (updateChat.position.order == 0 ? 0 : 1) - (i < chat.positions.length ? 1 : 0)];
                    int pos = 0;
                    if (updateChat.position.order != 0) {
                        new_positions[pos++] = updateChat.position;
                    }
                    for (int j = 0; j < chat.positions.length; j++) {
                        if (j != i) {
                            new_positions[pos++] = chat.positions[j];
                        }
                    }
                    assert pos == new_positions.length;

                    setChatPositions(chat, new_positions);
                }
                break;
            }
        }
    }

    private void setChatPositions(TdApi.Chat chat, TdApi.ChatPosition[] positions) {
        synchronized (Tuple.tuple(mainChatList, archivedChatList)) {
            synchronized (chat) {
                for (TdApi.ChatPosition position : chat.positions) {
                    if (position.list.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
                        if (!mainChatList.remove(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was not found in mainChatList".formatted(chat.id));
                        }
                    }
                    if (position.list.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR) {
                        if (!archivedChatList.remove(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was not found in archivedChatList".formatted(chat.id));
                        }
                    }
                }

                chat.positions = positions;

                for (TdApi.ChatPosition position : chat.positions) {
                    if (position.list.getConstructor() == TdApi.ChatListMain.CONSTRUCTOR) {
                        if (!mainChatList.add(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was already in mainChatList".formatted(chat.id));
                        }
                    }
                    if (position.list.getConstructor() == TdApi.ChatListArchive.CONSTRUCTOR) {
                        if (!archivedChatList.add(new OrderedChat(chat.id, position))) {
                            log.warn("Chat %d was already in archivedChatList".formatted(chat.id));
                        }
                    }
                }
            }
        }
    }

    private record OrderedChat(long chatId, TdApi.ChatPosition position) implements Comparable<OrderedChat> {

        @Override
        public int compareTo(OrderedChat o) {
            if (this.position.order != o.position.order) {
                return o.position.order < this.position.order ? -1 : 1;
            }
            if (this.chatId != o.chatId) {
                return o.chatId < this.chatId ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof OrderedChat(long id, TdApi.ChatPosition position1)) {
                return this.chatId == id && this.position.order == position1.order;
            }
            return false;
        }
    }
}
