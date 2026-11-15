#!/system/bin/sh
TITLE="$*"
if [ -z "$TITLE" ]; then exit 0; fi

ESCAPED=$(echo "$TITLE" | sed 's/ /%s/g' | tr -cd 'a-zA-Z0-9%s_\.-')

# Launch or bring UniTV to foreground
monkey -p com.integration.unitvsiptv -c android.intent.category.LAUNCHER 1

# Wait until VodSearchActivity is focused (up to ~15 seconds)
for i in $(seq 1 25); do
  FOCUS=$(dumpsys window windows 2>/dev/null | grep mFocusedApp)
  case "$FOCUS" in
    *VodSearchActivity*)
      break
      ;;
    *HomeActivity*)
      input tap 822 47
      sleep 0.8
      ;;
    *WelcomeActivity*)
      sleep 0.5
      ;;
    *com.integration.unitvsiptv*)
      input keyevent 4
      sleep 0.5
      ;;
    *)
      sleep 0.5
      ;;
  esac
done

# Wait if needed for VodSearchActivity
for i in 1 2 3; do
  FOCUS=$(dumpsys window windows 2>/dev/null | grep mFocusedApp)
  case "$FOCUS" in
    *VodSearchActivity*)
      break
      ;;
    *HomeActivity*)
      input tap 822 47
      sleep 0.8
      ;;
    *)
      sleep 0.5
      ;;
  esac
done

# Clear previous search
input tap 150 136
sleep 0.3

# Focus EditText
input tap 200 62
sleep 0.3

# Enter search text
input text "$ESCAPED"
sleep 0.8

# Tap Buscar button
input tap 318 136
sleep 0.5
input tap 318 136
