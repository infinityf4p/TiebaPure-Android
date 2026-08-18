package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex

@Composable
fun EditProfileRoute(
    viewModel: EditProfileViewModel,
    onBack: () -> Unit,
    onSaved: (UserProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    state.savedProfile?.let { profile -> LaunchedEffect(profile) { onSaved(profile) } }
    EditProfileScreen(
        state = state,
        onBack = onBack,
        onNicknameChange = viewModel::setNickname,
        onIntroductionChange = viewModel::setIntroduction,
        onSexChange = viewModel::setSex,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

@Composable
fun EditProfileScreen(
    state: EditProfileUiState,
    onBack: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onIntroductionChange: (String) -> Unit,
    onSexChange: (UserProfileSex) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AccountScreenHeader(
            title = "编辑资料",
            onBack = onBack,
            actionLabel = if (state.isSaving) "保存中" else "保存",
            actionEnabled = state.canSave,
            onAction = onSave,
        )
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.nickname,
                onValueChange = onNicknameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("昵称") },
                singleLine = true,
                isError = state.nicknameError != null,
                supportingText = state.nicknameError?.let { message -> { Text(message) } },
            )
            OutlinedTextField(
                value = state.introduction,
                onValueChange = onIntroductionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("个人简介") },
                minLines = 3,
                maxLines = 5,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("性别", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        UserProfileSex.Unspecified to "不显示",
                        UserProfileSex.Male to "男",
                        UserProfileSex.Female to "女",
                    ).forEach { (sex, title) ->
                        FilterChip(
                            selected = state.sex == sex,
                            onClick = { onSexChange(sex) },
                            label = { Text(title) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "资料更新结果以贴吧服务端审核为准。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
