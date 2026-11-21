const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("public_profiles").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("chats").where("participantUids", "array-contains", ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      online: true,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("PII isolation: users/{uid} is strictly owner-only while public_profiles/{uid} allows get only", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice Silva",
      phoneNumber: "+5511999998888",
      email: "alice@example.com",
      online: true,
      lastSeen: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Bob cannot read or list Alice's private user document
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());

  // Alice writes public_profile (including animated photoUrl & bannerUrl) and phone_lookup
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      photoUrl: "https://cdn.example.com/alice_avatar.mp4",
      bannerUrl: "https://cdn.example.com/alice_banner.jpg",
      updatedAt: new Date(Date.now() - 1200),
    })
  );
  await assertSucceeds(
    aliceDb.collection("public_profiles").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice Silva",
      about: "Disponível no Zap2",
      photoUrl: "https://cdn.example.com/alice_avatar.mp4",
      bannerUrl: "https://cdn.example.com/alice_banner.jpg",
      online: true,
      lastSeen: past,
      updatedAt: past,
    })
  );
  await assertSucceeds(
    aliceDb.collection("phone_lookup").doc("5511999998888").set({
      uid: ALICE_UID,
      displayName: "Alice Silva",
      updatedAt: past,
    })
  );

  // Bob can get exact public_profile and exact phone_lookup, but CANNOT list either collection
  await assertSucceeds(bobDb.collection("public_profiles").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("public_profiles").get());
  await assertSucceeds(bobDb.collection("phone_lookup").doc("5511999998888").get());
  await assertFails(bobDb.collection("phone_lookup").get());

  // Reject ghost fields and future timestamps
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("public_profiles").doc(ALICE_UID).update({
      online: false,
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("Contacts subcollection is isolated per owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("contact_bob").set({
      ownerId: ALICE_UID,
      peerUid: BOB_UID,
      displayName: "Bob",
      phoneNumber: "5511988887777",
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("contacts").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("contacts").get());

  // Re-saving an existing contact via partial update preserves createdAt
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("contact_bob").update({
      peerUid: BOB_UID,
      displayName: "Bob Atualizado",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  // Overwriting createdAt on an existing contact is rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("contact_bob").set({
      ownerId: ALICE_UID,
      peerUid: BOB_UID,
      displayName: "Bob",
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("Direct chat get-before-create succeeds when non-existent and isolates once created", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const chatId = `direct_${ALICE_UID}_${BOB_UID}`;
  const past = new Date(Date.now() - 2000);

  // Mirrors ChatRepository.getOrCreateDirectChat checking .get() before .set()
  const preSnap = await assertSucceeds(aliceDb.collection("chats").doc(chatId).get());
  if (preSnap.exists) {
    throw new Error("Expected direct chat not to exist yet");
  }

  await assertSucceeds(
    aliceDb.collection("chats").doc(chatId).set({
      chatType: "DIRECT",
      title: "Bob",
      createdBy: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      lastMessageText: "Conversa iniciada",
      lastMessageType: "SYSTEM",
      lastMessageSenderId: ALICE_UID,
      lastMessageAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Participant Bob can read the existing direct chat; non-participant Charlie is denied
  await assertSucceeds(bobDb.collection("chats").doc(chatId).get());
  await assertFails(charlieDb.collection("chats").doc(chatId).get());
});

test("Group chats, messages, and typing indicator parity & security", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const past = new Date(Date.now() - 5000);

  // Alice creates a GROUP chat with Bob
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").set({
      chatType: "GROUP",
      title: "Time Zap2",
      description: "Grupo oficial",
      createdBy: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      lastMessageText: "Grupo criado",
      lastMessageType: "SYSTEM",
      lastMessageSenderId: ALICE_UID,
      lastMessageAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Filtered query succeeds for participant, unfiltered query fails, non-participant fails
  await assertSucceeds(aliceDb.collection("chats").where("participantUids", "array-contains", ALICE_UID).get());
  await assertFails(aliceDb.collection("chats").get());
  await assertFails(charlieDb.collection("chats").doc("group_1").get());

  // Non-admin Bob cannot add Charlie
  await assertFails(
    bobDb.collection("chats").doc("group_1").update({
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Admin Alice adds Charlie and promotes Bob to admin
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").update({
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      adminUids: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob", [CHARLIE_UID]: "Charlie" },
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob updates typing indicator
  await assertSucceeds(
    bobDb.collection("chats").doc("group_1").collection("typing").doc(BOB_UID).set({
      uid: BOB_UID,
      displayName: "Bob",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      typing: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  // Bob cannot spoof Alice's typing status
  await assertFails(
    bobDb.collection("chats").doc("group_1").collection("typing").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      typing: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob sends a message and updates chat preview
  await assertSucceeds(
    bobDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").set({
      chatId: "group_1",
      senderId: BOB_UID,
      senderName: "Bob",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "TEXT",
      text: "Salve galera!",
      deliveredTo: [BOB_UID],
      readBy: [BOB_UID],
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    bobDb.collection("chats").doc("group_1").update({
      lastMessageText: "Salve galera!",
      lastMessageType: "TEXT",
      lastMessageSenderId: BOB_UID,
      lastMessageAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Charlie queries messages with participantUids filter
  await assertSucceeds(
    charlieDb.collection("chats").doc("group_1").collection("messages")
      .where("participantUids", "array-contains", CHARLIE_UID)
      .get()
  );

  // Charlie marks message as read
  await assertSucceeds(
    charlieDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").update({
      readBy: [BOB_UID, CHARLIE_UID],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Inline compressed image data URL succeeds; oversized >800,000 chars fails
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").collection("messages").doc("msg_img").set({
      chatId: "group_1",
      senderId: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "IMAGE",
      text: "",
      mediaUrl: "data:image/jpeg;base64," + "A".repeat(2048),
      deliveredTo: [ALICE_UID],
      readBy: [ALICE_UID],
      createdAt: new Date(Date.now() - 400),
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(
    aliceDb.collection("chats").doc("group_1").collection("messages").doc("msg_too_big").set({
      chatId: "group_1",
      senderId: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "IMAGE",
      text: "",
      mediaUrl: "data:image/jpeg;base64," + "A".repeat(800100),
      deliveredTo: [ALICE_UID],
      readBy: [ALICE_UID],
      createdAt: new Date(Date.now() - 300),
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // VIDEO message succeeds
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").collection("messages").doc("msg_vid").set({
      chatId: "group_1",
      senderId: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "VIDEO",
      text: "",
      mediaUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/video.mp4?alt=media",
      mediaDurationMs: 4200,
      fileName: "video.mp4",
      fileSizeBytes: 12582912,
      mimeType: "video/mp4",
      deliveredTo: [ALICE_UID],
      readBy: [ALICE_UID],
      createdAt: new Date(Date.now() - 250),
      updatedAt: new Date(Date.now() - 250),
    })
  );

  // PDF, APK, and ZIP messages with metadata succeed and chat preview accepts them
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").collection("messages").doc("msg_pdf").set({
      chatId: "group_1",
      senderId: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "PDF",
      text: "Manual.pdf",
      mediaUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/manual.pdf?alt=media",
      fileName: "Manual.pdf",
      fileSizeBytes: 4194304,
      mimeType: "application/pdf",
      deliveredTo: [ALICE_UID],
      readBy: [ALICE_UID],
      createdAt: new Date(Date.now() - 240),
      updatedAt: new Date(Date.now() - 240),
    })
  );
  await assertSucceeds(
    bobDb.collection("chats").doc("group_1").collection("messages").doc("msg_apk").set({
      chatId: "group_1",
      senderId: BOB_UID,
      senderName: "Bob",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "APK",
      text: "app-release.apk",
      mediaUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/app.apk?alt=media",
      fileName: "app-release.apk",
      fileSizeBytes: 25165824,
      mimeType: "application/vnd.android.package-archive",
      deliveredTo: [BOB_UID],
      readBy: [BOB_UID],
      createdAt: new Date(Date.now() - 230),
      updatedAt: new Date(Date.now() - 230),
    })
  );
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").collection("messages").doc("msg_zip").set({
      chatId: "group_1",
      senderId: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "ZIP",
      text: "pacote.zip",
      mediaUrl: "https://firebasestorage.googleapis.com/v0/b/demo/o/pacote.zip?alt=media",
      fileName: "pacote.zip",
      fileSizeBytes: 8388608,
      mimeType: "application/zip",
      deliveredTo: [ALICE_UID],
      readBy: [ALICE_UID],
      createdAt: new Date(Date.now() - 220),
      updatedAt: new Date(Date.now() - 220),
    })
  );
  await assertSucceeds(
    aliceDb.collection("chats").doc("group_1").update({
      lastMessageText: "pacote.zip",
      lastMessageType: "ZIP",
      lastMessageSenderId: ALICE_UID,
      lastMessageAt: new Date(Date.now() - 220),
      updatedAt: new Date(Date.now() - 220),
    })
  );

  // Sender Bob can edit his own message; Charlie cannot edit Bob's message
  await assertSucceeds(
    bobDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").update({
      text: "Salve galera editado!",
      edited: true,
      editedAt: new Date(Date.now() - 200),
      updatedAt: new Date(Date.now() - 200),
    })
  );
  await assertFails(
    charlieDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").update({
      text: "Hackeado",
      edited: true,
      editedAt: new Date(Date.now() - 150),
      updatedAt: new Date(Date.now() - 150),
    })
  );

  // Participant Charlie can delete msg_1 for himself via deletedFor
  await assertSucceeds(
    charlieDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").update({
      deletedFor: [CHARLIE_UID],
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Sender Bob can hard-delete msg_1 for everyone; Charlie cannot hard-delete Bob's message
  await assertFails(
    charlieDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").delete()
  );
  await assertSucceeds(
    bobDb.collection("chats").doc("group_1").collection("messages").doc("msg_1").delete()
  );
});

test("WebRTC call signaling lifecycle and terminal state locking", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const past = new Date(Date.now() - 3000);

  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").set({
      callerId: ALICE_UID,
      callerName: "Alice",
      calleeId: BOB_UID,
      calleeName: "Bob",
      chatId: "direct_alice_bob",
      participantUids: [ALICE_UID, BOB_UID],
      callType: "VIDEO",
      status: "RINGING",
      offerSdp: "v=0\r\no=- 123 2 IN IP4 127.0.0.1",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Charlie cannot read call
  await assertFails(charlieDb.collection("calls").doc("call_1").get());

  // Caller adds ICE candidate
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").collection("callerCandidates").doc("cand_1").set({
      senderId: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      sdpMid: "0",
      sdpMLineIndex: 0,
      sdp: "candidate:1 1 UDP 2122252543 192.168.1.2 50000 typ host",
      createdAt: new Date(Date.now() - 1500),
    })
  );

  // Callee answers call
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").update({
      answerSdp: "v=0\r\no=- 456 2 IN IP4 127.0.0.1",
      status: "CONNECTED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Caller ends call
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").update({
      status: "ENDED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Terminal state lock: cannot reopen ENDED call
  await assertFails(
    bobDb.collection("calls").doc("call_1").update({
      status: "CONNECTED",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Free chunked storage rules enforce participant isolation, public non-scraping, and owner backups", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const past = new Date(Date.now() - 2000);

  // Create chat between Alice and Bob
  await assertSucceeds(
    aliceDb.collection("chats").doc("chat_free").set({
      chatType: "DIRECT",
      title: "Bob",
      createdBy: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID, BOB_UID],
      lastMessageText: "Oi",
      lastMessageType: "TEXT",
      lastMessageSenderId: ALICE_UID,
      lastMessageAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Alice uploads chunk 0 of a file to chat_free
  await assertSucceeds(
    aliceDb.collection("chats").doc("chat_free").collection("file_chunks").doc("file1_0").set({
      chatId: "chat_free",
      fileId: "file1",
      chunkIndex: 0,
      totalChunks: 2,
      senderId: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      data: "SGVsbG8=",
      createdAt: past,
    })
  );

  // Participant Bob can read chunk; non-participant Charlie is denied
  await assertSucceeds(bobDb.collection("chats").doc("chat_free").collection("file_chunks").doc("file1_0").get());
  await assertFails(charlieDb.collection("chats").doc("chat_free").collection("file_chunks").doc("file1_0").get());

  // Charlie cannot upload into chat_free
  await assertFails(
    charlieDb.collection("chats").doc("chat_free").collection("file_chunks").doc("bad_0").set({
      chatId: "chat_free",
      fileId: "bad",
      chunkIndex: 0,
      totalChunks: 1,
      senderId: CHARLIE_UID,
      participantUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      data: "AAAA",
      createdAt: past,
    })
  );

  // Public profile media chunks: Alice uploads, Bob can exact-get, nobody can list-scrape
  await assertSucceeds(
    aliceDb.collection("public_media_chunks").doc("av1_0").set({
      ownerId: ALICE_UID,
      mediaId: "av1",
      chunkIndex: 0,
      totalChunks: 1,
      mimeType: "video/mp4",
      data: "AAAA",
      createdAt: past,
    })
  );
  await assertSucceeds(bobDb.collection("public_media_chunks").doc("av1_0").get());
  await assertFails(bobDb.collection("public_media_chunks").get());
  await assertFails(bobDb.collection("public_media_chunks").doc("av1_0").delete());

  // User backup chunks: strictly owner-only
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("backup_chunks").doc("bk1_0").set({
      ownerId: ALICE_UID,
      backupId: "bk1",
      chunkIndex: 0,
      totalChunks: 1,
      data: "UEsDBAo=",
      createdAt: past,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("backup_chunks").doc("bk1_0").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("backup_chunks").doc("bk1_0").get());
});
