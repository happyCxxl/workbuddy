"""Windows 受限令牌命令沙箱：工作区内可写、工作区外写被拒。

API 序列（spike 已在非管理员会话验证；S16.1 按 DSH 同款补 Low 完整性层）：
1. `CreateRestrictedToken`：WRITE_RESTRICTED + restricting SIDs = [登录会话 SID, Everyone, 工作区写 SID]，
   再经 ctypes `SetTokenInformation(TokenIntegrityLevel)` 降到 Low（S-1-16-4096）；
2. 工作区 DACL 合并写 SID 的可继承 Modify ACE + Everyone 的 `FILE_DELETE_CHILD` 容器级 deny（合并原 DACL）；
3. 工作区目录打 Low no-write-up 标签（ctypes `InitializeAcl` + `AddMandatoryAce` + `SetNamedSecurityInfoW(DACL|LABEL)`），
   低完整性写检查据此挡住工作区外 Everyone 可写的位置；
4. `CreateProcessAsUser` 启动子进程（python/pywin32，无需提权）。

约束（来自验证，不是猜测）：
- 登录 SID 与 Everyone 必须在 restricting 列表里，否则子进程 `0xC0000142`（DLL 初始化失败）；
- 子进程不能带 `CREATE_NO_WINDOW`（同样 `0xC0000142`），共享宿主控制台；
- 打标签需要目录的 WRITE_OWNER（用户自己的工作区通常是 FullControl）；缺失时失败关闭；
- 只拦写：读、网络、进程可见性不受限（主文档 §6 v1 边界）；
- Low 标签常驻：同用户其它 Low 进程也能写工作区（DSH 同款残留边界，文档记录）；
- 子进程输出经文件句柄重定向，父进程按上限截断读取。
"""

# pyright: reportMissingTypeStubs=false
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import hashlib
import os
import shutil
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any

MODIFY = 0x1301BF
_LOGON_SID_ATTRIBUTE = 0xC0000000
_MAX_DRAIN_SECONDS = 5.0

# ctypes 直调（pywin32 没有 TokenIntegrityLevel / 标签 SACL 的封装）
_LOW_LABEL_SID = "S-1-16-4096"
_WORLD_SID = "S-1-1-0"
_SE_FILE_OBJECT = 1
_DACL_INFO = 0x4
_LABEL_INFO = 0x10
_ACL_REVISION = 2
_NO_WRITE_UP = 0x1
_LABEL_ACE_FLAGS = 0x3  # 容器与对象都继承
_FILE_DELETE_CHILD = 0x40
_TOKEN_INTEGRITY_LEVEL = 25
_SE_GROUP_INTEGRITY = 0x20
_DENY_ACE_FLAGS = 0x2  # 仅容器继承（该权限按目录评估）


class _SidAndAttributes(ctypes.Structure):
    """TOKEN_MANDATORY_LABEL 的结构体部分。"""

    _fields_ = [("Sid", ctypes.c_void_p), ("Attributes", wt.DWORD)]


class SandboxError(RuntimeError):
    """沙箱不可用：非 Windows、目录不存在或 API 失败。"""


def _access_denied(exc: Exception) -> bool:
    """是否为拒绝访问（winerror 5）：目录不属于当前用户，拿不到所有权/WRITE_DAC。"""
    if getattr(exc, "winerror", None) == 5:
        return True
    args = getattr(exc, "args", ())
    return bool(args) and args[0] == 5


@dataclass
class SandboxResult:
    """一次命令执行的结果：退出码、输出与截断/超时标记。"""

    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False
    truncated: bool = False

    def to_text(self) -> str:
        """渲染为模型可读文本。"""
        parts = [f"exit={self.exit_code}"]
        if self.timed_out:
            parts.append("（超时，已终止进程树）")
        if self.stdout:
            parts.append(self.stdout)
        if self.stderr:
            parts.append(f"[stderr]\n{self.stderr}")
        if self.truncated:
            parts.append("（输出已截断）")
        return "\n".join(parts)


def write_sid_for(work_dir: str) -> str:
    """按工作区派生写 SID：S-1-4-x-y（路径规范化后哈希，同一目录恒定）。"""
    canonical = os.path.normcase(os.path.realpath(work_dir))
    digest = hashlib.sha256(canonical.encode("utf-8")).digest()
    first = int.from_bytes(digest[:4], "little") % (2**30 - 1) + 1
    second = int.from_bytes(digest[4:8], "little") % (2**30 - 1) + 1
    return f"S-1-4-{first}-{second}"


def _win32() -> tuple[Any, Any, Any, Any, Any]:
    """加载 pywin32 五件套：非 Windows 直接宣判沙箱不可用。"""
    if sys.platform != "win32":
        raise SandboxError("命令沙箱仅支持 Windows")
    import win32api
    import win32con
    import win32file
    import win32process
    import win32security

    return win32api, win32con, win32file, win32process, win32security


def _decode(data: bytes) -> str:
    """子进程输出解码：优先 UTF-8，其次系统 ANSI（mbcs），最后替换解码。"""
    for encoding in ("utf-8", "mbcs"):
        try:
            return data.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return data.decode("utf-8", errors="replace")


_advapi32_handle: Any = None


def _advapi32() -> Any:  # noqa: ANN401 —— WinDLL 无类型桩
    """加载 advapi32：Low 完整性令牌与目录标签只有 ctypes 路径。"""
    if sys.platform != "win32":
        raise SandboxError("命令沙箱仅支持 Windows")
    global _advapi32_handle
    if _advapi32_handle is None:
        _advapi32_handle = ctypes.WinDLL("advapi32", use_last_error=True)
    return _advapi32_handle


def _sid_ptr(sid_text: str) -> ctypes.c_void_p:
    """字符串 SID → SID 指针（调用方负责 LocalFree）。"""
    handle = ctypes.c_void_p()
    if not _advapi32().ConvertStringSidToSidW(ctypes.c_wchar_p(sid_text), ctypes.byref(handle)):
        raise SandboxError(f"SID 解析失败：{sid_text}（错误码 {ctypes.get_last_error()}）")
    return handle


def _sid_string(handle: ctypes.c_void_p) -> str:
    """SID 指针 → 字符串 SID。"""
    text = ctypes.c_void_p()
    if not _advapi32().ConvertSidToStringSidW(handle, ctypes.byref(text)):
        raise SandboxError(f"SID 转字符串失败（错误码 {ctypes.get_last_error()}）")
    if not text.value:
        raise SandboxError("SID 转字符串返回空指针")
    return ctypes.wstring_at(text.value)


def _local_free(handle: ctypes.c_void_p) -> None:
    """释放 LocalAlloc 分配的内存（GetNamedSecurityInfoW 的描述符等）。"""
    ctypes.windll.kernel32.LocalFree(handle)


def read_label(path: str) -> str:
    """读目录的完整性标签 SID；没有标签返回空串。"""
    advapi32 = _advapi32()
    descriptor = ctypes.c_void_p()
    result = advapi32.GetNamedSecurityInfoW(
        ctypes.c_wchar_p(path),
        _SE_FILE_OBJECT,
        _LABEL_INFO,
        None,
        None,
        None,
        None,
        ctypes.byref(descriptor),
    )
    if result != 0:
        raise SandboxError(f"读取目录标签失败：{path}（错误码 {result}）")
    try:
        present = wt.BOOL()
        sacl = ctypes.c_void_p()
        defaulted = wt.BOOL()
        if not advapi32.GetSecurityDescriptorSacl(
            descriptor, ctypes.byref(present), ctypes.byref(sacl), ctypes.byref(defaulted)
        ):
            raise SandboxError(f"读 SACL 失败：{path}（错误码 {ctypes.get_last_error()}）")
        if not present.value or not sacl.value:
            return ""
        ace = ctypes.c_void_p()
        if not advapi32.GetAce(sacl, 0, ctypes.byref(ace)):
            raise SandboxError(f"读标签 ACE 失败：{path}（错误码 {ctypes.get_last_error()}）")
        # SYSTEM_MANDATORY_LABEL_ACE：ACE_HEADER(4) + Mask(4) + 内联 SID
        if not ace.value:
            raise SandboxError(f"标签 ACE 地址为空：{path}")
        return _sid_string(ctypes.c_void_p(ace.value + 8))
    finally:
        _local_free(descriptor)


def _label_acl(sid_text: str) -> ctypes.Array[ctypes.c_char]:
    """构造单条 no-write-up、可继承的标签 ACL。"""
    advapi32 = _advapi32()
    label_sid = _sid_ptr(sid_text)
    sid_length = advapi32.GetLengthSid(label_sid)
    size = 8 + 8 + sid_length  # ACL 头 + 标签 ACE 固定部分 + 内联 SID
    buffer = ctypes.create_string_buffer(size)
    if not advapi32.InitializeAcl(buffer, size, _ACL_REVISION):
        raise SandboxError(f"初始化标签 ACL 失败（错误码 {ctypes.get_last_error()}）")
    if not advapi32.AddMandatoryAce(buffer, _ACL_REVISION, _LABEL_ACE_FLAGS, _NO_WRITE_UP, label_sid):
        raise SandboxError(f"写入标签 ACE 失败（错误码 {ctypes.get_last_error()}）")
    return buffer


def apply_label(path: str) -> None:
    """给目录打 Low 标签：DACL 原样带过，标签与 DACL 一次落盘（DSH 同款）。"""
    advapi32 = _advapi32()
    descriptor = ctypes.c_void_p()
    result = advapi32.GetNamedSecurityInfoW(
        ctypes.c_wchar_p(path),
        _SE_FILE_OBJECT,
        _DACL_INFO | _LABEL_INFO,
        None,
        None,
        None,
        None,
        ctypes.byref(descriptor),
    )
    if result != 0:
        raise SandboxError(f"读取目录安全信息失败：{path}（错误码 {result}）")
    try:
        dacl = ctypes.c_void_p()
        present = wt.BOOL()
        if not advapi32.GetSecurityDescriptorDacl(
            descriptor, ctypes.byref(present), ctypes.byref(dacl), ctypes.byref(wt.BOOL())
        ):
            raise SandboxError(f"读 DACL 失败：{path}（错误码 {ctypes.get_last_error()}）")
        label = _label_acl(_LOW_LABEL_SID)
        result = advapi32.SetNamedSecurityInfoW(
            ctypes.c_wchar_p(path),
            _SE_FILE_OBJECT,
            _DACL_INFO | _LABEL_INFO,
            None,
            None,
            dacl if present.value else None,
            ctypes.cast(label, ctypes.c_void_p),
        )
        if result != 0:
            raise SandboxError(f"写目录标签失败：{path}（错误码 {result}；需要目录 WRITE_OWNER，通常为 FullControl）")
    finally:
        _local_free(descriptor)


def lower_token_integrity(token: Any) -> None:  # noqa: ANN401 —— pywin32 句柄无类型桩
    """把令牌降到 Low 完整性（TokenIntegrityLevel）。"""
    advapi32 = _advapi32()
    label_sid = _sid_ptr(_LOW_LABEL_SID)
    sid_length = advapi32.GetLengthSid(label_sid)
    size = ctypes.sizeof(_SidAndAttributes) + sid_length
    buffer = ctypes.create_string_buffer(size)
    struct = _SidAndAttributes(label_sid, _SE_GROUP_INTEGRITY)
    ctypes.memmove(buffer, ctypes.byref(struct), ctypes.sizeof(struct))
    if not advapi32.SetTokenInformation(int(token), _TOKEN_INTEGRITY_LEVEL, buffer, size):
        raise SandboxError(f"设置令牌完整性失败（错误码 {ctypes.get_last_error()}）")


def token_integrity(token: Any) -> str:  # noqa: ANN401 —— pywin32 句柄无类型桩
    """读令牌的完整性 SID（测试与排障用）。"""
    advapi32 = _advapi32()
    needed = wt.DWORD()
    advapi32.GetTokenInformation(int(token), _TOKEN_INTEGRITY_LEVEL, None, 0, ctypes.byref(needed))
    buffer = ctypes.create_string_buffer(needed.value)
    if not advapi32.GetTokenInformation(int(token), _TOKEN_INTEGRITY_LEVEL, buffer, needed.value, ctypes.byref(needed)):
        raise SandboxError(f"读取令牌完整性失败（错误码 {ctypes.get_last_error()}）")
    label = ctypes.cast(buffer, ctypes.POINTER(_SidAndAttributes)).contents
    return _sid_string(ctypes.c_void_p(label.Sid))


class WindowsSandbox:
    """一次运行 = 授权工作区 + 打 Low 标签 + 造低完整性限制令牌 + 启动子进程（同步，调用方放线程里跑）。"""

    def __init__(self, work_dir: str, timeout_seconds: float, output_cap: int) -> None:
        self._api = _win32()
        self._work_dir = os.path.abspath(work_dir)
        if not os.path.isdir(self._work_dir):
            raise SandboxError(f"工作目录不存在：{self._work_dir}")
        self._timeout = timeout_seconds
        self._cap = output_cap

    def run(self, command: str) -> SandboxResult:
        """在沙箱内执行命令：成功返回退出码与输出，超时报超时并杀进程树。"""
        _api, _con, _file, _proc, security = self._api
        write_sid = security.ConvertStringSidToSid(write_sid_for(self._work_dir))
        try:
            self._grant(write_sid)
            if read_label(self._work_dir) != _LOW_LABEL_SID:
                apply_label(self._work_dir)
        except Exception as exc:
            if not _access_denied(exc):
                raise
            raise SandboxError(
                "SANDBOX_UNAVAILABLE：无法为工作区加固权限（目录不属于当前用户，缺少所有权/WRITE_DAC）。"
                f"工作区：{self._work_dir}；请换一个自己拥有写权限的目录，"
                "或在 config 中把 tools.sandbox 设为 off 显式关闭沙箱。"
            ) from exc
        token = self._restricted_token(write_sid)
        try:
            return self._spawn(token, command)
        finally:
            token.Close()

    def _grant(self, write_sid: Any) -> None:  # noqa: ANN401 —— pywin32 SID 无类型桩
        """确保工作区 DACL 带写 SID 的可继承 Modify ACE 与 Everyone 的删除子项 deny（幂等）。"""
        win32con = self._api[1]
        win32security = self._api[4]
        sid_string = win32security.ConvertSidToStringSid(write_sid)
        world_string = _WORLD_SID
        sd = win32security.GetNamedSecurityInfo(
            self._work_dir, win32security.SE_FILE_OBJECT, win32security.DACL_SECURITY_INFORMATION
        )
        dacl = sd.GetSecurityDescriptorDacl()
        has_grant = False
        has_deny = False
        if dacl is not None:
            for index in range(dacl.GetAceCount()):
                (ace_type, ace_flags), mask, existing_sid = dacl.GetAce(index)
                existing = win32security.ConvertSidToStringSid(existing_sid)
                if (
                    ace_type == win32security.ACCESS_ALLOWED_ACE_TYPE
                    and existing == sid_string
                    and mask & MODIFY == MODIFY
                ):
                    has_grant = True
                if (
                    ace_type == win32security.ACCESS_DENIED_ACE_TYPE
                    and existing == world_string
                    and mask & _FILE_DELETE_CHILD
                ):
                    has_deny = True
        if has_grant and has_deny:
            return
        revision = dacl.GetAclRevision() if dacl is not None else win32security.ACL_REVISION
        new_acl = win32security.ACL()
        if dacl is not None:
            for index in range(dacl.GetAceCount()):
                (ace_type, ace_flags), mask, existing_sid = dacl.GetAce(index)
                if ace_type == win32security.ACCESS_ALLOWED_ACE_TYPE:
                    new_acl.AddAccessAllowedAceEx(revision, ace_flags, mask, existing_sid)
                elif ace_type == win32security.ACCESS_DENIED_ACE_TYPE:
                    new_acl.AddAccessDeniedAceEx(revision, ace_flags, mask, existing_sid)
        if not has_grant:
            new_acl.AddAccessAllowedAceEx(
                revision, win32con.CONTAINER_INHERIT_ACE | win32con.OBJECT_INHERIT_ACE, MODIFY, write_sid
            )
        if not has_deny:
            world_sid = win32security.ConvertStringSidToSid(_WORLD_SID)
            new_acl.AddAccessDeniedAceEx(revision, _DENY_ACE_FLAGS, _FILE_DELETE_CHILD, world_sid)
        win32security.SetNamedSecurityInfo(
            self._work_dir,
            win32security.SE_FILE_OBJECT,
            win32security.DACL_SECURITY_INFORMATION,
            None,
            None,
            new_acl,
            None,
        )

    def _restricted_token(self, write_sid: Any) -> Any:  # noqa: ANN401 —— pywin32 句柄无类型桩
        """造限制令牌并降到 Low 完整性：WRITE_RESTRICTED 只拦写，Low 标签挡住 Everyone 写。"""
        win32api, win32con, _file, _proc, security = self._api
        token = security.OpenProcessToken(
            win32api.GetCurrentProcess(),
            win32con.TOKEN_QUERY | win32con.TOKEN_DUPLICATE | win32con.TOKEN_ASSIGN_PRIMARY | 0x80,
        )
        try:
            logon_sid = None
            for sid, attributes in security.GetTokenInformation(token, security.TokenGroups):
                if attributes & _LOGON_SID_ATTRIBUTE:
                    logon_sid = sid
                    break
            if logon_sid is None:
                raise SandboxError("令牌缺少登录会话 SID")
            everyone = security.ConvertStringSidToSid(_WORLD_SID)
            flags = 0x1 | 0x4 | 0x8  # DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED
            restricted = security.CreateRestrictedToken(
                token, flags, [], [], [(logon_sid, 0), (everyone, 0), (write_sid, 0)]
            )
        finally:
            token.Close()
        try:
            lower_token_integrity(restricted)
        except Exception:
            restricted.Close()
            raise
        return restricted

    def _spawn(self, token: Any, command: str) -> SandboxResult:  # noqa: ANN401 —— pywin32 句柄无类型桩
        """启动子进程：输出走继承文件句柄，父进程等待并读取。"""
        win32api, win32con, win32file, win32process, security = self._api
        run_dir = os.path.join(self._work_dir, f".lingxi-run-{uuid.uuid4().hex[:8]}")
        os.makedirs(run_dir)
        out_path = os.path.join(run_dir, "stdout.txt")
        err_path = os.path.join(run_dir, "stderr.txt")
        attributes = security.SECURITY_ATTRIBUTES()
        attributes.bInheritHandle = 1
        out_handle = win32file.CreateFile(
            out_path,
            win32con.GENERIC_WRITE,
            win32con.FILE_SHARE_READ | win32con.FILE_SHARE_WRITE,
            attributes,
            win32con.CREATE_ALWAYS,
            win32con.FILE_ATTRIBUTE_NORMAL,
            None,
        )
        err_handle = win32file.CreateFile(
            err_path,
            win32con.GENERIC_WRITE,
            win32con.FILE_SHARE_READ | win32con.FILE_SHARE_WRITE,
            attributes,
            win32con.CREATE_ALWAYS,
            win32con.FILE_ATTRIBUTE_NORMAL,
            None,
        )
        startup = win32process.STARTUPINFO()
        startup.dwFlags = win32con.STARTF_USESTDHANDLES | win32con.STARTF_USESHOWWINDOW
        startup.wShowWindow = win32con.SW_HIDE
        startup.hStdInput = win32api.GetStdHandle(-10)  # STD_INPUT_HANDLE
        startup.hStdOutput = out_handle
        startup.hStdError = err_handle
        # 子进程临时目录重定向到本次运行目录：环境 TEMP 在受限令牌下不可写，直接沿用会让需要临时文件的工具失败
        environment = {**os.environ, "TEMP": run_dir, "TMP": run_dir}
        try:
            handle, _thread, pid, _tid = win32process.CreateProcessAsUser(
                token,
                None,
                f"cmd.exe /c {command}",
                None,
                None,
                True,
                0,
                environment,
                self._work_dir,
                startup,
            )
        finally:
            out_handle.Close()
            err_handle.Close()
            time.sleep(0)  # 让子进程先拿到句柄所有权

        timed_out = False
        deadline = time.monotonic() + self._timeout
        code = win32con.STILL_ACTIVE
        try:
            while time.monotonic() < deadline:
                code = win32process.GetExitCodeProcess(handle)
                if code != win32con.STILL_ACTIVE:
                    break
                time.sleep(0.05)
            if code == win32con.STILL_ACTIVE:
                timed_out = True
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)  # noqa: S603 —— 杀自己启动的进程树，参数为整数 pid
                drain_deadline = time.monotonic() + _MAX_DRAIN_SECONDS
                while time.monotonic() < drain_deadline:
                    code = win32process.GetExitCodeProcess(handle)
                    if code != win32con.STILL_ACTIVE:
                        break
                    time.sleep(0.05)
                if code == win32con.STILL_ACTIVE:
                    try:
                        win32process.TerminateProcess(handle, 1)
                    except Exception:  # noqa: BLE001 —— 可能已被 taskkill 收尸，判不可杀即视为已终止
                        code = -1
                    code = code if code != win32con.STILL_ACTIVE else -1
        finally:
            handle.Close()

        stdout_raw = b""
        stderr_raw = b""
        try:
            with open(out_path, "rb") as fh:
                stdout_raw = fh.read()
            with open(err_path, "rb") as fh:
                stderr_raw = fh.read()
        except OSError:
            pass
        finally:
            shutil.rmtree(run_dir, ignore_errors=True)

        stdout = _decode(stdout_raw)
        stderr = _decode(stderr_raw)
        if len(stdout) > self._cap:
            stdout = stdout[: self._cap]
            truncated = True
        else:
            truncated = False
        remaining = max(self._cap - len(stdout), 0)
        if len(stderr) > remaining:
            stderr = stderr[:remaining]
            truncated = True
        return SandboxResult(
            exit_code=int(code),
            stdout=stdout.strip(),
            stderr=stderr.strip(),
            timed_out=timed_out,
            truncated=truncated,
        )
