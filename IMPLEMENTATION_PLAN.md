# Voice-First SMS Messaging Implementation Plan

## Overview
Transform the current TTS/ASR test app into a voice-first messaging prototype with SMS integration, while keeping the original demo accessible.

## Development Approach
- **Testing Device**: Pixel 9 Pro - Speech emulator
- **Test-Driven**: Write tests for each component before implementation
- **Incremental**: Test each feature on emulator as we build

## Implementation Steps

### Phase 1: SMS Permissions & Setup
**Files to modify:**
- `AndroidManifest.xml`

**Permissions needed:**
- `android.permission.READ_SMS` - Read SMS messages
- `android.permission.RECEIVE_SMS` - Get new messages in real-time  
- `android.permission.SEND_SMS` - Send SMS replies
- `android.permission.READ_CONTACTS` - Resolve contact names

**Testing:**
- Verify permissions dialog appears on first launch
- Test with pre-populated SMS on emulator

---

### Phase 2: Data Models & Architecture
**New files to create:**
- `data/models/Conversation.kt`
- `data/models/Message.kt` 
- `data/repository/SmsRepository.kt`

**Data classes:**
```kotlin
data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isRead: Boolean,
    val isOutgoing: Boolean
)

data class Conversation(
    val threadId: Long,
    val address: String,
    val contactName: String?,
    val lastMessage: Message?,
    val unreadCount: Int
)
```

**Testing:**
- Unit tests for data models
- Repository tests with mock data

---

### Phase 3: SMS Repository Implementation
**Features:**
- Read from `content://sms/inbox` and `content://sms/sent`
- Group by thread/conversation
- Track read/unread status
- Sort by most recent

**Testing on emulator:**
1. Send test SMS to emulator using `adb emu sms send <number> <message>`
2. Verify messages appear in repository
3. Test marking as read

---

### Phase 4: Navigation & UI Structure
**New components:**
- Bottom navigation with two tabs: "Messages" and "Demo"
- Keep existing demo screen intact
- Add navigation component

**UI Updates:**
- `MainActivity.kt` - Add navigation host
- Create `MessagesScreen.kt` composable
- Create `DemoScreen.kt` (move existing UI here)

**Testing:**
- Verify tab switching works
- Ensure demo functionality remains intact

---

### Phase 5: Conversation List UI
**Design specs:**
- Large cards (min height 80dp)
- Contact name/number
- Last message preview (max 2 lines)
- Unread badge indicator
- Two buttons per card:
  - **Play** button (speaker icon)
  - **Reply** button (mic icon)

**Testing on emulator:**
- Load with various message counts
- Test scroll performance
- Verify touch targets are large enough

---

### Phase 6: Message Playback Implementation
**Play button behavior:**
- If unread messages exist → Read all unread in conversation
- If all read → Read just the last message
- Format: "[Contact name] says: [message text]"
- Mark as read after playback

**Testing:**
- Send multiple unread messages to emulator
- Test playback queue
- Verify read status updates
- Test interruption handling

---

### Phase 7: Voice Reply Dialog
**Reply flow:**
1. Tap Reply button → Opens modal dialog
2. Auto-start voice recognition
3. Show live transcription in text field
4. Two buttons:
   - **Send** - Stop recognition, send displayed text
   - **Retry** - Clear text, restart recognition
5. Auto-stop on 3 seconds of silence

**Dialog UI:**
- Semi-transparent background
- Large text display area
- Visual recording indicator
- Large Send/Retry buttons

**Testing on emulator:**
- Test voice input using emulator microphone
- Verify transcription appears in real-time
- Test send/retry functionality
- Verify SMS actually sends

---

### Phase 8: Contact Resolution
**Implementation:**
- Query `ContactsContract.CommonDataKinds.Phone`
- Cache lookups in memory
- Fallback to formatted phone number

**Testing:**
- Add test contacts to emulator
- Verify names resolve correctly
- Test with unknown numbers

---

### Phase 9: Real-time Updates
**Features:**
- BroadcastReceiver for incoming SMS
- Update conversation list automatically
- Show notification badge

**Testing:**
- Send SMS while app is open
- Verify UI updates without refresh
- Test while on different tabs

---

### Phase 10: Testing Suite
**Test categories:**
1. **Unit Tests**
   - Data models
   - Repository logic
   - Contact resolution

2. **UI Tests (Espresso)**
   - Navigation between tabs
   - Button click handlers
   - Dialog interactions

3. **Integration Tests**
   - SMS sending/receiving
   - TTS playback
   - ASR capture

4. **Manual Testing on Emulator**
   - Full user flows
   - Edge cases (empty states, errors)
   - Performance with many messages

---

## Testing Commands for Emulator

### Send test SMS:
```bash
adb emu sms send 5551234567 "Test message"
```

### Add test contacts:
```bash
# Use emulator UI or adb to add contacts
```

### Test voice input:
- Use computer microphone when emulator is focused
- Or use Android Studio's AVD extended controls

---

## Success Criteria
- [ ] Can view SMS conversations in large, accessible cards
- [ ] Play button reads unread or last message via TTS
- [ ] Reply button opens voice capture dialog
- [ ] Live transcription visible during recording
- [ ] Can send SMS via voice without typing
- [ ] Can switch to original demo mode
- [ ] All features work on Pixel 9 Pro emulator
- [ ] Tests pass for all components

---

## Notes
- Keep accessibility in mind (large targets, high contrast)
- Minimize typing - voice first approach
- Test each phase thoroughly before moving on
- Commit after each successful phase